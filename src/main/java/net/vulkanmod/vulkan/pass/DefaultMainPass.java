package net.vulkanmod.vulkan.pass;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class DefaultMainPass implements MainPass {

    public static DefaultMainPass create() {
        return new DefaultMainPass();
    }

    private final Framebuffer mainFramebuffer;

    private RenderPass mainRenderPass;
    private RenderPass auxRenderPass;

    private GpuTexture[] colorAttachmentTextures;
    private GpuTextureView[] colorAttachmentTextureViews;
    private GpuTexture depthAttachmentTexture;

    DefaultMainPass() {
        this.mainFramebuffer = Renderer.getInstance().getSwapChain();

        createRenderPasses();
        createAttachmentTextures();
    }

    private void createRenderPasses() {
        // Render pass principal: DONT_CARE no load (GPU não precisa de ler
        // tile memory de main memory — inicia tile limpo). Ideal para TBDR.
        RenderPass.Builder builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);

        this.mainRenderPass = builder.build();

        // FIX #7: Render pass auxiliar (usado em rebindMainTarget para GUI/postprocess).
        //
        // PROBLEMA ORIGINAL:
        // auxRenderPass usava LOAD_OP_LOAD para COR e DEPTH.
        // Em Mali-G52 (TBDR), LOAD_OP_LOAD força o driver a:
        //   1. Ler todo o framebuffer de main memory para tile memory no início de cada tile
        //   2. Processar os novos draw calls
        //   3. Escrever o tile de volta para main memory
        // Isso elimina a principal vantagem do TBDR (processar em tile sem tocar main memory).
        // Cada chamada a rebindMainTarget() (ex: transição 3D→GUI) gerava um tile flush
        // completo → queda de FPS de 30-50% em cenas com GUI complexa.
        //
        // FIX: DEPTH usa DONT_CARE na reentrada — a GUI não usa o depth buffer do
        // frame 3D anterior. COR mantém LOAD (necessário para compositar GUI sobre 3D).
        builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getDepthAttachmentInfo().setOps(
            VK_ATTACHMENT_LOAD_OP_DONT_CARE,  // FIX: era LOAD — tile flush desnecessário em TBDR
            VK_ATTACHMENT_STORE_OP_STORE);

        this.auxRenderPass = builder.build();
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        SwapChain framebuffer = Renderer.getInstance().getSwapChain();

        net.vulkanmod.render.chunk.buffer.UploadManager.INSTANCE
            .recordAcquireBarriers(commandBuffer);

        // Pre-transition bound textures to SHADER_READ_ONLY_OPTIMAL BEFORE the render
        // pass begins. Image layout transitions (VkImageMemoryBarrier) are INVALID
        // inside an active render pass per Vulkan spec. On Mali this causes silent
        // corruption → purple/invisible textures.
        try (MemoryStack transStack = MemoryStack.stackPush()) {
            for (int i = 0; i < VTextureSelector.SIZE; i++) {
                VulkanImage tex = VTextureSelector.getImage(i);
                if (tex == null)
                    continue;

                int layout = tex.getCurrentLayout();

                if (layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    continue;

                switch (layout) {
                    case VK_IMAGE_LAYOUT_UNDEFINED:
                    case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
                    case VK_IMAGE_LAYOUT_PRESENT_SRC_KHR:
                        tex.readOnlyLayout(transStack, commandBuffer);
                        break;
                    default:
                        break;
                }
            }
        }

        Renderer.getInstance().beginRenderPass(this.mainRenderPass, framebuffer);
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            SwapChain framebuffer = Renderer.getInstance().getSwapChain();
            framebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }
    }

    @Override
    public void cleanUp() {
        this.mainRenderPass.cleanUp();
        this.auxRenderPass.cleanUp();
    }

    @Override
    public void onResize() {
        this.createAttachmentTextures();
    }

    public void rebindMainTarget() {
        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            return;

        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.auxRenderPass, swapChain);
    }

    @Override
    public void bindAsTexture() {
        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            swapChain.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(swapChain.getColorAttachment());
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.colorAttachmentTextures[Renderer.getCurrentImage()];
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.colorAttachmentTextureViews[Renderer.getCurrentImage()];
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.depthAttachmentTexture;
    }

    private void createAttachmentTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        var swapChainImages = swapChain.getImages();

        if (swapChain.getWidth() == 0 && swapChain.getHeight() == 0)
            return;

        int imageCount = swapChainImages.size();
        this.colorAttachmentTextures = new GpuTexture[imageCount];
        this.colorAttachmentTextureViews = new GpuTextureView[imageCount];

        for (int i = 0; i < imageCount; ++i) {
            VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(swapChainImages.get(i));
            GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
            this.colorAttachmentTextures[i] = attachmentTexture;
            this.colorAttachmentTextureViews[i] = attachmentTextureView;
        }

        this.depthAttachmentTexture = device.gpuTextureFromVulkanImage(swapChain.getDepthAttachment());
    }
}
