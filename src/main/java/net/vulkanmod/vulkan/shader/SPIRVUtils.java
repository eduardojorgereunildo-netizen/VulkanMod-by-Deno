package net.vulkanmod.vulkan.shader;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.config.Platform;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.NativeResource;
import org.lwjgl.util.shaderc.ShadercIncludeResolveI;
import org.lwjgl.util.shaderc.ShadercIncludeResult;
import org.lwjgl.util.shaderc.ShadercIncludeResultReleaseI;
import org.lwjgl.vulkan.VK11;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memASCII;
import static org.lwjgl.util.shaderc.Shaderc.*;

public class SPIRVUtils {
    private static final boolean DEBUG = true;
    private static final boolean OPTIMIZATIONS = false;

    private static long compiler;
    private static long options;

    private static final ShaderIncluder SHADER_INCLUDER = new ShaderIncluder();
    private static final ShaderReleaser SHADER_RELEASER = new ShaderReleaser();
    private static final long pUserData = 0;

    private static ObjectArrayList<String> includePaths;

    static {
        // FIX #1: guard Android — libshaderc.so não existe em Android ARM64
        // Sem este guard, o JVM crasha ao tentar carregar a biblioteca nativa.
        if (!Platform.isAndroid()) {
            initCompiler();
        }
    }

    private static void initCompiler() {
        compiler = shaderc_compiler_initialize();

        if (compiler == NULL) {
            throw new RuntimeException("Failed to create shader compiler");
        }

        options = shaderc_compile_options_initialize();

        if (options == NULL) {
            throw new RuntimeException("Failed to create compiler options");
        }

        if (OPTIMIZATIONS)
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);

        if (DEBUG)
            shaderc_compile_options_set_generate_debug_info(options);

        shaderc_compile_options_set_target_env(options, shaderc_env_version_vulkan_1_1, VK11.VK_API_VERSION_1_1);
        shaderc_compile_options_set_include_callbacks(options, SHADER_INCLUDER, SHADER_RELEASER, pUserData);

        includePaths = new ObjectArrayList<>();
        addIncludePath("/assets/vulkanmod/shaders/include/");
    }

    public static void addIncludePath(String path) {
        URL url = SPIRVUtils.class.getResource(path);

        if (url != null)
            includePaths.add(url.toExternalForm());
    }

    public static SPIRV compileShader(String filename, String source, ShaderKind shaderKind) {
        // FIX #1: Android usa SPIR-V pré-compilado — nunca compila em runtime.
        // Retorna null em vez de lançar exceção; os callers já têm guard isAndroid().
        // Lançar RuntimeException aqui causava crash garantido se algum caminho
        // esquecesse o guard, tornando a app não-arrancável em ARM64.
        if (Platform.isAndroid()) {
            return null;
        }

        if (source == null) {
            throw new NullPointerException("source for %s.%s is null".formatted(filename, shaderKind));
        }

        long result = shaderc_compile_into_spv(compiler, source, shaderKind.kind, filename, "main", options);

        if (result == NULL) {
            throw new RuntimeException("Failed to compile shader " + filename + " into SPIR-V");
        }

        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
            String errorMessage = shaderc_result_get_error_message(result);
            throw new RuntimeException("Failed to compile shader %s into SPIR-V:\n\t%s".formatted(filename, errorMessage));
        }

        return new SPIRV(result, shaderc_result_get_bytes(result));
    }

    public enum ShaderKind {
        VERTEX_SHADER(shaderc_glsl_vertex_shader),
        GEOMETRY_SHADER(shaderc_glsl_geometry_shader),
        FRAGMENT_SHADER(shaderc_glsl_fragment_shader),
        COMPUTE_SHADER(shaderc_glsl_compute_shader);

        private final int kind;

        ShaderKind(int kind) {
            this.kind = kind;
        }
    }

    private static class ShaderIncluder implements ShadercIncludeResolveI {

        private static final int MAX_PATH_LENGTH = 4096;

        @Override
        public long invoke(long user_data, long requested_source, int type, long requesting_source, long include_depth) {
            var requesting = memASCII(requesting_source);
            var requested = memASCII(requested_source);

            try (MemoryStack stack = MemoryStack.stackPush()) {
                Path path;

                for (String includePath : includePaths) {
                    path = Paths.get(new URI(String.format("%s%s", includePath, requested)));

                    if (Files.exists(path)) {
                        byte[] bytes = Files.readAllBytes(path);

                        return ShadercIncludeResult.malloc(stack)
                                                   .source_name(stack.ASCII(requested))
                                                   .content(stack.bytes(bytes))
                                                   .user_data(user_data).address();
                    }
                }
            } catch (IOException | URISyntaxException e) {
                throw new RuntimeException(e);
            }

            throw new RuntimeException(String.format("%s: Unable to find %s in include paths", requesting, requested));
        }
    }

    private static class ShaderReleaser implements ShadercIncludeResultReleaseI {

        @Override
        public void invoke(long user_data, long include_result) {
        }
    }

    public static final class SPIRV implements NativeResource {

        private final long handle;
        private ByteBuffer bytecode;

        public SPIRV(long handle, ByteBuffer bytecode) {
            this.handle = handle;
            this.bytecode = bytecode;
        }

        public ByteBuffer bytecode() {
            return bytecode;
        }

        @Override
        public void free() {
            bytecode = null;
        }
    }
}
