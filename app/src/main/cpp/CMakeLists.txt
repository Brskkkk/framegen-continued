cmake_minimum_required(VERSION 3.22.1)
project(vkinterp)

# --- Locate glslc bundled with the NDK (present since NDK r18+) to compile
# warp_blend.comp -> SPIR-V at build time. We deliberately do NOT link
# libshaderc into the app to do this at runtime - a build-time compile
# keeps the shipped app dependency-free and avoids any ABI mismatch risk
# with a bundled shaderc.
if(NOT DEFINED ANDROID_NDK)
    set(ANDROID_NDK ${CMAKE_ANDROID_NDK})
endif()

find_program(GLSLC_EXE glslc
    PATHS
        "${ANDROID_NDK}/shader-tools/linux-x86_64"
        "${ANDROID_NDK}/shader-tools/darwin-x86_64"
        "${ANDROID_NDK}/shader-tools/windows-x86_64"
    NO_DEFAULT_PATH
)
if(NOT GLSLC_EXE)
    # Fall back to searching PATH, in case a distro-provided glslc is
    # available even if the NDK's bundled copy wasn't found above.
    find_program(GLSLC_EXE glslc)
endif()
if(NOT GLSLC_EXE)
    message(FATAL_ERROR "glslc not found (looked in ${ANDROID_NDK}/shader-tools and PATH) - cannot compile warp_blend.comp")
endif()

set(SHADER_SRC "${CMAKE_CURRENT_SOURCE_DIR}/shaders/warp_blend.comp")
set(SHADER_SPV "${CMAKE_CURRENT_BINARY_DIR}/warp_blend.spv")
set(SHADER_HEADER "${CMAKE_CURRENT_BINARY_DIR}/warp_blend_spv.h")

# --target-env=vulkan1.0 keeps the emitted SPIR-V at version 1.0, matching
# the core-1.0-only Vulkan instance/device we create in vk_interp.cpp -
# this is the actual "don't need a special Vulkan feature" compatibility
# requirement, enforced at the shader level too, not just the API calls.
add_custom_command(
    OUTPUT "${SHADER_SPV}"
    COMMAND "${GLSLC_EXE}" --target-env=vulkan1.0 -O -o "${SHADER_SPV}" "${SHADER_SRC}"
    DEPENDS "${SHADER_SRC}"
    COMMENT "Compiling warp_blend.comp -> SPIR-V (vulkan1.0 target)"
    VERBATIM
)

add_custom_command(
    OUTPUT "${SHADER_HEADER}"
    COMMAND "${CMAKE_COMMAND}"
        -DSPV_INPUT=${SHADER_SPV}
        -DOUT_HEADER=${SHADER_HEADER}
        -DARRAY_NAME=g_warp_blend_spv
        -P "${CMAKE_CURRENT_SOURCE_DIR}/bin2h.cmake"
    DEPENDS "${SHADER_SPV}" "${CMAKE_CURRENT_SOURCE_DIR}/bin2h.cmake"
    COMMENT "Embedding warp_blend.spv as a C header"
    VERBATIM
)

add_custom_target(warp_blend_shader DEPENDS "${SHADER_HEADER}")

add_library(vkinterp SHARED vk_interp.cpp)
add_dependencies(vkinterp warp_blend_shader)

target_include_directories(vkinterp PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")

find_library(log-lib log)
find_library(vulkan-lib vulkan)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(vkinterp
    ${log-lib}
    ${vulkan-lib}
    ${jnigraphics-lib}
)
