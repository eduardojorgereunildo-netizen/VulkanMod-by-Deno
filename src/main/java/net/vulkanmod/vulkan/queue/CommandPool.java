package net.vulkanmod.vulkan.queue;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class CommandPool {
    // FIX #4: timeout máximo para operações GPU — evita freeze permanente se
    // o GPU travar (overtemperature, throttling agressivo em Mali ARM64).
    // Long.MAX_VALUE anterior ≈ 292 anos: GPU trava → app bloqueia para sempre.
    // 5 segundos é tempo suficiente para qualquer frame normal e detecta travamentos reais.
    private static final long FENCE_TIMEOUT_NS = 5_000_000_000L; // 5 segundos

    long id;

    private final List<CommandBuffer> commandBuffers = new ObjectArrayList<>();
    private final java.util.Queue<CommandBuffer> availableCmdBuffers = new ArrayDeque<>();

    CommandPool(int queueFamilyIndex) {
        this.createCommandPool(queueFamilyIndex);
    }

    public void createCommandPool(int queueFamily) {
        try (MemoryStack stack = stackPush()) {

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(queueFamily);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pCommandPool = stack.mallocLong(1);

            if (vkCreateCommandPool(Vulkan.getVkDevice(), poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }

            this.id = pCommandPool.get(0);
        }
    }

    public CommandBuffer getCommandBuffer() {
        try (MemoryStack stack = stackPush()) {
            return getCommandBuffer(stack);
        }
    }

    public CommandBuffer getCommandBuffer(MemoryStack stack) {
        if (availableCmdBuffers.isEmpty()) {
            allocateCommandBuffers(stack);
        }

        CommandBuffer commandBuffer = availableCmdBuffers.poll();
        return commandBuffer;
    }

    private void allocateCommandBuffers(MemoryStack stack) {
        final int size = 10;

        VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
        allocInfo.sType$Default();
        allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
        allocInfo.commandPool(id);
        allocInfo.commandBufferCount(size);

        PointerBuffer pCommandBuffer = stack.mallocPointer(size);
        vkAllocateCommandBuffers(Vulkan.getVkDevice(), allocInfo, pCommandBuffer);

        VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
        fenceInfo.sType$Default();
        fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

        VkSemaphoreCreateInfo semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack);
        semaphoreCreateInfo.sType$Default();

        for (int i = 0; i < size; ++i) {
            LongBuffer pFence = stack.mallocLong(1);
            vkCreateFence(Vulkan.getVkDevice(), fenceInfo, null, pFence);

            LongBuffer pSemaphore = stack.mallocLong(1);
            vkCreateSemaphore(Vulkan.getVkDevice(), semaphoreCreateInfo, null, pSemaphore);

            VkCommandBuffer vkCommandBuffer = new VkCommandBuffer(pCommandBuffer.get(i), Vulkan.getVkDevice());
            CommandBuffer commandBuffer = new CommandBuffer(this, vkCommandBuffer, pFence.get(0), pSemaphore.get(0));
            commandBuffers.add(commandBuffer);
            availableCmdBuffers.add(commandBuffer);
        }
    }

    public void addToAvailable(CommandBuffer commandBuffer) {
        this.availableCmdBuffers.add(commandBuffer);
    }

    public void cleanUp() {
        for (CommandBuffer commandBuffer : commandBuffers) {
            vkDestroyFence(Vulkan.getVkDevice(), commandBuffer.fence, null);
            vkDestroySemaphore(Vulkan.getVkDevice(), commandBuffer.semaphore, null);
        }
        vkResetCommandPool(Vulkan.getVkDevice(), id, VK_COMMAND_POOL_RESET_RELEASE_RESOURCES_BIT);
        vkDestroyCommandPool(Vulkan.getVkDevice(), id, null);
    }

    public long getId() {
        return id;
    }

    public static class CommandBuffer {
        public final CommandPool commandPool;
        public final VkCommandBuffer handle;
        public final long fence;
        public final long semaphore;

        boolean submitted;
        boolean recording;

        public CommandBuffer(CommandPool commandPool, VkCommandBuffer handle, long fence, long semaphore) {
            this.commandPool = commandPool;
            this.handle = handle;
            this.fence = fence;
            this.semaphore = semaphore;
        }

        public VkCommandBuffer getHandle() {
            return handle;
        }

        public long getFence() {
            return fence;
        }

        public long getSemaphore() {
            return semaphore;
        }

        public boolean isSubmitted() {
            return submitted;
        }

        public boolean isRecording() {
            return recording;
        }

        public void begin(MemoryStack stack) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(this.handle, beginInfo);

            this.recording = true;
        }

        public long submitCommands(MemoryStack stack, VkQueue queue, boolean useSemaphore) {
            long fence = this.fence;

            vkEndCommandBuffer(this.handle);

            vkResetFences(Vulkan.getVkDevice(), new long[]{this.fence});

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pCommandBuffers(stack.pointers(this.handle));

            if (useSemaphore) {
                submitInfo.pSignalSemaphores(stack.longs(this.semaphore));
            }

            int err = vkQueueSubmit(queue, submitInfo, fence);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to submit command buffer: " + err);
            }

            this.recording = false;
            this.submitted = true;
            return fence;
        }

        public void reset() {
            VkDevice device = Vulkan.getVkDevice();

            // FIX #4: timeout finito — Long.MAX_VALUE anterior bloqueava para
            // sempre se o GPU Mali travasse por throttling ou overtemperature.
            // 5 segundos deteta GPU travado e regista aviso em vez de freeze permanente.
            int result = vkWaitForFences(device, new long[]{this.fence}, true, FENCE_TIMEOUT_NS);

            if (result == VK_TIMEOUT) {
                Initializer.LOGGER.error(
                    "CommandBuffer fence timeout ({}ms) — GPU pode estar travado. "
                    + "A forçar reset para libertar o pool.",
                    FENCE_TIMEOUT_NS / 1_000_000);
                // Continua mesmo em timeout: melhor libertar o buffer que bloquear
            } else if (result != VK_SUCCESS) {
                Initializer.LOGGER.warn("vkWaitForFences retornou código inesperado: {}", result);
            }

            vkResetFences(device, new long[]{this.fence});
            vkResetCommandBuffer(this.handle, 0);

            this.submitted = false;
            this.recording = false;
            this.commandPool.addToAvailable(this);
        }
    }
}
