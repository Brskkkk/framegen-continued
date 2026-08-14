#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <cstring>
#include <cstdint>
#include <vector>

#include "warp_blend_spv.h"

#define LOG_TAG "VkInterp"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define VK_CHECK(x) do { VkResult _r = (x); if (_r != VK_SUCCESS) { LOGE("%s failed: %d", #x, _r); return false; } } while (0)

namespace {

struct Params {
    int32_t width;
    int32_t height;
    float mvx;
    float mvy;
    float alpha;
};

struct VkState {
    bool initialized = false;

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;

    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet = VK_NULL_HANDLE;

    // Sized/(re)allocated on demand in ensureBuffers() when the capture
    // resolution changes - not every call, since that's the expensive part.
    int bufW = 0;
    int bufH = 0;
    VkBuffer frame1Buf = VK_NULL_HANDLE, frame2Buf = VK_NULL_HANDLE, outBuf = VK_NULL_HANDLE, paramsBuf = VK_NULL_HANDLE;
    VkDeviceMemory frame1Mem = VK_NULL_HANDLE, frame2Mem = VK_NULL_HANDLE, outMem = VK_NULL_HANDLE, paramsMem = VK_NULL_HANDLE;
    void *frame1Mapped = nullptr, *frame2Mapped = nullptr, *outMapped = nullptr, *paramsMapped = nullptr;
};

VkState g_state;

uint32_t findMemoryType(VkPhysicalDevice phys, uint32_t typeBits, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(phys, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) && (memProps.memoryTypes[i].propertyFlags & props) == props) {
            return i;
        }
    }
    return UINT32_MAX;
}

bool createBuffer(VkDevice device, VkPhysicalDevice phys, VkDeviceSize size, VkBufferUsageFlags usage,
                   VkBuffer *outBuffer, VkDeviceMemory *outMemory, void **outMapped) {
    VkBufferCreateInfo bufInfo{};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = size;
    bufInfo.usage = usage;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (vkCreateBuffer(device, &bufInfo, nullptr, outBuffer) != VK_SUCCESS) return false;

    VkMemoryRequirements memReq;
    vkGetBufferMemoryRequirements(device, *outBuffer, &memReq);

    uint32_t memType = findMemoryType(phys, memReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (memType == UINT32_MAX) return false;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = memType;
    if (vkAllocateMemory(device, &allocInfo, nullptr, outMemory) != VK_SUCCESS) return false;

    if (vkBindBufferMemory(device, *outBuffer, *outMemory, 0) != VK_SUCCESS) return false;
    if (outMapped) {
        if (vkMapMemory(device, *outMemory, 0, size, 0, outMapped) != VK_SUCCESS) return false;
    }
    return true;
}

void destroyBuffer(VkDevice device, VkBuffer &buf, VkDeviceMemory &mem, void *&mapped) {
    if (mapped) { vkUnmapMemory(device, mem); mapped = nullptr; }
    if (buf) { vkDestroyBuffer(device, buf, nullptr); buf = VK_NULL_HANDLE; }
    if (mem) { vkFreeMemory(device, mem, nullptr); mem = VK_NULL_HANDLE; }
}

bool ensureBuffers(int width, int height) {
    VkState &s = g_state;
    if (s.bufW == width && s.bufH == height && s.frame1Buf != VK_NULL_HANDLE) return true;

    vkDeviceWaitIdle(s.device);

    destroyBuffer(s.device, s.frame1Buf, s.frame1Mem, s.frame1Mapped);
    destroyBuffer(s.device, s.frame2Buf, s.frame2Mem, s.frame2Mapped);
    destroyBuffer(s.device, s.outBuf, s.outMem, s.outMapped);
    destroyBuffer(s.device, s.paramsBuf, s.paramsMem, s.paramsMapped);

    VkDeviceSize pixelBufSize = (VkDeviceSize) width * height * 4;

    if (!createBuffer(s.device, s.physicalDevice, pixelBufSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                       &s.frame1Buf, &s.frame1Mem, &s.frame1Mapped)) return false;
    if (!createBuffer(s.device, s.physicalDevice, pixelBufSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                       &s.frame2Buf, &s.frame2Mem, &s.frame2Mapped)) return false;
    if (!createBuffer(s.device, s.physicalDevice, pixelBufSize, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                       &s.outBuf, &s.outMem, &s.outMapped)) return false;
    if (!createBuffer(s.device, s.physicalDevice, sizeof(Params), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                       &s.paramsBuf, &s.paramsMem, &s.paramsMapped)) return false;

    VkDescriptorBufferInfo bufInfos[4] = {};
    bufInfos[0] = {s.frame1Buf, 0, pixelBufSize};
    bufInfos[1] = {s.frame2Buf, 0, pixelBufSize};
    bufInfos[2] = {s.outBuf, 0, pixelBufSize};
    bufInfos[3] = {s.paramsBuf, 0, sizeof(Params)};

    VkWriteDescriptorSet writes[4] = {};
    VkDescriptorType types[4] = {
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
    };
    for (int i = 0; i < 4; i++) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = s.descriptorSet;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = types[i];
        writes[i].pBufferInfo = &bufInfos[i];
    }
    vkUpdateDescriptorSets(s.device, 4, writes, 0, nullptr);

    // Re-record the command buffer for the new dispatch size.
    vkResetCommandBuffer(s.commandBuffer, 0);
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (vkBeginCommandBuffer(s.commandBuffer, &beginInfo) != VK_SUCCESS) return false;

    vkCmdBindPipeline(s.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, s.pipeline);
    vkCmdBindDescriptorSets(s.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, s.pipelineLayout,
                             0, 1, &s.descriptorSet, 0, nullptr);
    uint32_t groupsX = (width + 15) / 16;
    uint32_t groupsY = (height + 15) / 16;
    vkCmdDispatch(s.commandBuffer, groupsX, groupsY, 1);

    if (vkEndCommandBuffer(s.commandBuffer) != VK_SUCCESS) return false;

    s.bufW = width;
    s.bufH = height;
    return true;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_idk_frameinterp_VulkanWarpInterpolator_nativeInit(JNIEnv *env, jobject /*thiz*/) {
    VkState &s = g_state;
    if (s.initialized) return JNI_TRUE;

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "FrameInterpVk";
    appInfo.apiVersion = VK_API_VERSION_1_0; // core 1.0 only - no special feature/extension requirement

    VkInstanceCreateInfo instInfo{};
    instInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instInfo.pApplicationInfo = &appInfo;
    // No enabled extensions or layers at all - offscreen compute needs
    // neither a surface/swapchain nor validation layers to run.
    VK_CHECK(vkCreateInstance(&instInfo, nullptr, &s.instance));

    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(s.instance, &deviceCount, nullptr);
    if (deviceCount == 0) {
        LOGE("No Vulkan-capable physical devices found");
        return JNI_FALSE;
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    vkEnumeratePhysicalDevices(s.instance, &deviceCount, devices.data());

    bool found = false;
    for (auto dev : devices) {
        uint32_t qCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, nullptr);
        std::vector<VkQueueFamilyProperties> qProps(qCount);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, &qCount, qProps.data());
        for (uint32_t i = 0; i < qCount; i++) {
            if (qProps[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                s.physicalDevice = dev;
                s.queueFamilyIndex = i;
                found = true;
                break;
            }
        }
        if (found) break;
    }
    if (!found) {
        LOGE("No compute-capable queue family found on any physical device");
        return JNI_FALSE;
    }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = s.queueFamilyIndex;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    // No device extensions enabled either.
    VK_CHECK(vkCreateDevice(s.physicalDevice, &deviceInfo, nullptr, &s.device));

    vkGetDeviceQueue(s.device, s.queueFamilyIndex, 0, &s.queue);

    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = s.queueFamilyIndex;
    VK_CHECK(vkCreateCommandPool(s.device, &poolInfo, nullptr, &s.commandPool));

    VkCommandBufferAllocateInfo cbInfo{};
    cbInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbInfo.commandPool = s.commandPool;
    cbInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbInfo.commandBufferCount = 1;
    VK_CHECK(vkAllocateCommandBuffers(s.device, &cbInfo, &s.commandBuffer));

    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    VK_CHECK(vkCreateFence(s.device, &fenceInfo, nullptr, &s.fence));

    VkDescriptorSetLayoutBinding bindings[4] = {};
    for (int i = 0; i < 3; i++) {
        bindings[i].binding = i;
        bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    bindings[3].binding = 3;
    bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    bindings[3].descriptorCount = 1;
    bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutCreateInfo dslInfo{};
    dslInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslInfo.bindingCount = 4;
    dslInfo.pBindings = bindings;
    VK_CHECK(vkCreateDescriptorSetLayout(s.device, &dslInfo, nullptr, &s.descriptorSetLayout));

    VkPipelineLayoutCreateInfo plInfo{};
    plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plInfo.setLayoutCount = 1;
    plInfo.pSetLayouts = &s.descriptorSetLayout;
    VK_CHECK(vkCreatePipelineLayout(s.device, &plInfo, nullptr, &s.pipelineLayout));

    VkShaderModuleCreateInfo smInfo{};
    smInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    smInfo.codeSize = g_warp_blend_spv_len;
    smInfo.pCode = reinterpret_cast<const uint32_t *>(g_warp_blend_spv);
    VkShaderModule shaderModule;
    VK_CHECK(vkCreateShaderModule(s.device, &smInfo, nullptr, &shaderModule));

    VkPipelineShaderStageCreateInfo stageInfo{};
    stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stageInfo.module = shaderModule;
    stageInfo.pName = "main";

    VkComputePipelineCreateInfo cpInfo{};
    cpInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    cpInfo.stage = stageInfo;
    cpInfo.layout = s.pipelineLayout;
    VkResult pipelineResult = vkCreateComputePipelines(s.device, VK_NULL_HANDLE, 1, &cpInfo, nullptr, &s.pipeline);
    vkDestroyShaderModule(s.device, shaderModule, nullptr);
    if (pipelineResult != VK_SUCCESS) {
        LOGE("vkCreateComputePipelines failed: %d", pipelineResult);
        return JNI_FALSE;
    }

    VkDescriptorPoolSize poolSizes[2] = {};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[0].descriptorCount = 3;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSizes[1].descriptorCount = 1;

    VkDescriptorPoolCreateInfo dpInfo{};
    dpInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpInfo.maxSets = 1;
    dpInfo.poolSizeCount = 2;
    dpInfo.pPoolSizes = poolSizes;
    VK_CHECK(vkCreateDescriptorPool(s.device, &dpInfo, nullptr, &s.descriptorPool));

    VkDescriptorSetAllocateInfo dsAllocInfo{};
    dsAllocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsAllocInfo.descriptorPool = s.descriptorPool;
    dsAllocInfo.descriptorSetCount = 1;
    dsAllocInfo.pSetLayouts = &s.descriptorSetLayout;
    VK_CHECK(vkAllocateDescriptorSets(s.device, &dsAllocInfo, &s.descriptorSet));

    s.initialized = true;
    LOGI("Vulkan compute interpolator initialized (core 1.0, no extensions)");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_idk_frameinterp_VulkanWarpInterpolator_nativeInterpolate(
    JNIEnv *env, jobject /*thiz*/,
    jobject bitmap1, jobject bitmap2, jobject outBitmap,
    jfloat mvx, jfloat mvy, jfloat alpha) {

    VkState &s = g_state;
    if (!s.initialized) return JNI_FALSE;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap1, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Expected ARGB_8888 bitmaps, got format %d", info.format);
        return JNI_FALSE;
    }
    int width = (int) info.width;
    int height = (int) info.height;
    uint32_t stride = info.stride;

    if (!ensureBuffers(width, height)) return JNI_FALSE;

    auto copyBitmapIn = [&](jobject bmp, void *dst) -> bool {
        void *src = nullptr;
        if (AndroidBitmap_lockPixels(env, bmp, &src) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
        if (stride == (uint32_t) width * 4) {
            memcpy(dst, src, (size_t) width * height * 4);
        } else {
            // Row stride has padding - copy row by row.
            auto *srcBytes = static_cast<uint8_t *>(src);
            auto *dstBytes = static_cast<uint8_t *>(dst);
            for (int y = 0; y < height; y++) {
                memcpy(dstBytes + (size_t) y * width * 4, srcBytes + (size_t) y * stride, (size_t) width * 4);
            }
        }
        AndroidBitmap_unlockPixels(env, bmp);
        return true;
    };

    if (!copyBitmapIn(bitmap1, s.frame1Mapped)) return JNI_FALSE;
    if (!copyBitmapIn(bitmap2, s.frame2Mapped)) return JNI_FALSE;

    Params params{width, height, mvx, mvy, alpha};
    memcpy(s.paramsMapped, &params, sizeof(Params));

    if (vkWaitForFences(s.device, 1, &s.fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) return JNI_FALSE;
    if (vkResetFences(s.device, 1, &s.fence) != VK_SUCCESS) return JNI_FALSE;

    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &s.commandBuffer;
    if (vkQueueSubmit(s.queue, 1, &submitInfo, s.fence) != VK_SUCCESS) return JNI_FALSE;

    if (vkWaitForFences(s.device, 1, &s.fence, VK_TRUE, 2000000000ULL /* 2s */) != VK_SUCCESS) {
        LOGE("vkWaitForFences timed out waiting for compute dispatch");
        return JNI_FALSE;
    }

    AndroidBitmapInfo outInfo{};
    if (AndroidBitmap_getInfo(env, outBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    void *outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    if (outInfo.stride == (uint32_t) width * 4) {
        memcpy(outPixels, s.outMapped, (size_t) width * height * 4);
    } else {
        auto *srcBytes = static_cast<uint8_t *>(s.outMapped);
        auto *dstBytes = static_cast<uint8_t *>(outPixels);
        for (int y = 0; y < height; y++) {
            memcpy(dstBytes + (size_t) y * outInfo.stride, srcBytes + (size_t) y * width * 4, (size_t) width * 4);
        }
    }
    AndroidBitmap_unlockPixels(env, outBitmap);

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_idk_frameinterp_VulkanWarpInterpolator_nativeRelease(JNIEnv *env, jobject /*thiz*/) {
    VkState &s = g_state;
    if (!s.initialized) return;

    vkDeviceWaitIdle(s.device);

    destroyBuffer(s.device, s.frame1Buf, s.frame1Mem, s.frame1Mapped);
    destroyBuffer(s.device, s.frame2Buf, s.frame2Mem, s.frame2Mapped);
    destroyBuffer(s.device, s.outBuf, s.outMem, s.outMapped);
    destroyBuffer(s.device, s.paramsBuf, s.paramsMem, s.paramsMapped);

    if (s.descriptorPool) vkDestroyDescriptorPool(s.device, s.descriptorPool, nullptr);
    if (s.pipeline) vkDestroyPipeline(s.device, s.pipeline, nullptr);
    if (s.pipelineLayout) vkDestroyPipelineLayout(s.device, s.pipelineLayout, nullptr);
    if (s.descriptorSetLayout) vkDestroyDescriptorSetLayout(s.device, s.descriptorSetLayout, nullptr);
    if (s.fence) vkDestroyFence(s.device, s.fence, nullptr);
    if (s.commandPool) vkDestroyCommandPool(s.device, s.commandPool, nullptr);
    if (s.device) vkDestroyDevice(s.device, nullptr);
    if (s.instance) vkDestroyInstance(s.instance, nullptr);

    s = VkState{};
}
