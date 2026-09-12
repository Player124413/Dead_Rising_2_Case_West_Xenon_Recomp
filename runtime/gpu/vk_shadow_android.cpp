// The Android Vulkan loader: where Turnip is actually loaded, and where every Vulkan
// call in this runtime ends up.
//
// Read the generator's docstring (tools/gen_vk_shadow.py) for WHY the runtime cannot
// link libvulkan on Android and what the shadow table replaces. This file is the half
// that is not mechanical — the three decisions the generated header cannot make:
//
//   1. WHICH LOADER. libadrenotools, when the player picked a custom GPU driver, because
//      that is the only rootless way to get one: it loads /system/lib64/libvulkan.so into
//      a private linker namespace behind a hook that redirects the loader's own
//      `dlopen("/vendor/lib64/hw/vulkan.adreno.so")` at a driver file in OUR storage. The
//      plain system loader otherwise — a phone without a driver imported must still play,
//      on whatever Qualcomm shipped, and "you need Turnip" is a recommendation this file
//      prints rather than a gate it enforces.
//
//   2. WHICH ENTRY POINTS COME FROM WHERE, in two phases. dlsym first (fast, and the only
//      thing that works before an instance exists), then vkGetInstanceProcAddr for
//      whatever dlsym returned null for. That second phase is not a belt-and-braces extra:
//      Android's libvulkan exports the core entry points OF THE VERSION THAT ANDROID
//      RELEASE SHIPPED — 1.1 on Android 9, 1.3 only from Android 13 — while this runtime
//      is Vulkan 1.3 (README, Requirements) and Turnip is 1.3+. On an Android 11 phone
//      `dlsym(handle, "vkCmdBeginRendering")` is null even though the driver implements
//      it, and the desktop build's direct link would fail to LOAD there. Phase 2 is what
//      makes one APK work from Android 9 to 15.
//
//   3. BCn, which is this title's hard GPU requirement and Adreno's historical gap.
//      gpu/vk_renderer.cpp requests `textureCompressionBC` as a REQUIRED device feature
//      ("the title's DXT1/3/5 textures are uploaded as BC") — every texture in the game is
//      a DXT block, so there is no degraded mode to fall back to. Adreno hardware decodes
//      BC from the 6xx generation on, but some drivers do not TELL you, and libadrenotools
//      ships a patcher for exactly that (adrenotools_get_bcn_type / adrenotools_patch_bcn).
//      It has to run after an instance exists and before vkCreateDevice, so it runs here,
//      in the CreateInstance wrapper — the one place that is provably between the two, and
//      the reason that wrapper exists as a function rather than as a table slot.
//
// EVERYTHING IN HERE IS COMPILED AWAY ON EVERY OTHER PLATFORM. The desktop runtime links
// Vulkan::Vulkan exactly as it always has and none of this code exists in it, so no
// measurement ever taken on Windows or Linux is about a different binary than before.

#if defined(__ANDROID__)

#define CWVK_NO_SHADOW 1   // we fill the table; the macros would rename the real functions
#include "vk_shadow_android.h"

#include <android/api-level.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>

// libadrenotools (bylaws, BSD-2-Clause) is a BUILD-TIME OPTIONAL dependency, and its
// absence must not silently become "custom drivers do not work here". runtime/CMakeLists.txt
// defines CW_HAVE_ADRENOTOOLS when it found and linked the library; without it the code
// below still builds and still runs, on the system driver, and says so in the boot log —
// which is the honest failure, as opposed to a link error nobody can diagnose on a phone.
#if defined(CW_HAVE_ADRENOTOOLS)
#include <adrenotools/bcenabler.h>
#include <adrenotools/driver.h>
#endif

#define CW_LOG(...) __android_log_print(ANDROID_LOG_INFO, "CaseWest", __VA_ARGS__)

namespace CwVk
{

Table g_table;

namespace
{

std::once_flag g_loaderOnce;
void* g_loader = nullptr;
char g_loaderSource[160] = "none";
bool g_ready = false;
int g_missingAfterPhase1 = 0;

// The env contract with the launcher (android/app/src/main/java/.../DriverManager.kt sets
// all of it before the runtime starts; nothing here reaches into Java).
//
//   CW_VK_DRIVER_DIR   app-INTERNAL directory holding the extracted driver's .so files.
//                      Internal and not sdcard, on adrenotools' own instruction: dlopen
//                      refuses a library any other app could rewrite.
//   CW_VK_DRIVER_NAME  the driver's soname — meta.json's `libraryName`, e.g.
//                      "vulkan.adreno.so". Never guessed from a directory listing: two
//                      Turnip builds can put different sonames in the same folder shape.
//   CW_VK_HOOK_DIR     applicationInfo.nativeLibraryDir, where the hook .so files live.
//                      Defaults to this library's own directory, which IS that directory
//                      (host_paths.cpp's dladdr), because a value that has to be passed in
//                      from Java is a value that can be passed in wrong.
//   CW_VK_TMP_DIR      a writable scratch directory, used only below API 29 where
//                      adrenotools has no memfd to patch libraries through.
//   CW_VK_TURBO        1 (the default) asks the KGSL driver for maximum clocks.
//   CW_VK_NO_CUSTOM_DRIVER  1 forces the system driver — the control arm, and the first
//                      thing to try when a device renders wrong only with Turnip loaded.
struct DriverChoice
{
    const char* dir = nullptr;
    const char* name = nullptr;
    bool wanted = false;
};

DriverChoice ReadDriverChoice()
{
    DriverChoice c;
    c.dir = getenv("CW_VK_DRIVER_DIR");
    c.name = getenv("CW_VK_DRIVER_NAME");
    const char* off = getenv("CW_VK_NO_CUSTOM_DRIVER");
    const bool forced = off && *off && *off != '0';
    c.wanted = !forced && c.dir && *c.dir && c.name && *c.name;
    if (c.dir && *c.dir && !c.wanted && !forced)
        fprintf(stderr, "[vk-loader] CW_VK_DRIVER_DIR=%s with no usable "
                        "CW_VK_DRIVER_NAME — falling back to the system driver. The "
                        "launcher sets both from the imported package's meta.json; if you "
                        "are setting them by hand, the name is `libraryName`.\n", c.dir);
    return c;
}

const char* HookDir()
{
    if (const char* env = getenv("CW_VK_HOOK_DIR"); env && *env)
        return env;
    // Our own directory. adrenotools' header is emphatic that this MUST be
    // getApplicationInfo().nativeLibraryDir, and it is: this file is compiled into
    // libcw_runtime.so, which lives there, and the hook libraries are packaged beside it.
    Dl_info info{};
    static char buf[512];
    if (dladdr(reinterpret_cast<void*>(&HookDir), &info) != 0 && info.dli_fname)
    {
        snprintf(buf, sizeof buf, "%s", info.dli_fname);
        if (char* slash = strrchr(buf, '/'))
            *slash = '\0';
        return buf;
    }
    return nullptr;
}

void* OpenSystemLoader()
{
    // The absolute path rather than the soname. A bare "libvulkan.so" is resolved through
    // the caller's linker namespace, and on Android the app namespace reaches the system
    // one only for the NDK's declared public libraries — libvulkan IS one of them, so both
    // spellings work today, but the absolute path is the one that keeps working when a
    // device's namespace configuration does not include it.
    static const char* kCandidates[] = {
        "/system/lib64/libvulkan.so",
        "/apex/com.android.runtime/lib64/bionic/libvulkan.so",
        "libvulkan.so",
    };
    for (const char* c : kCandidates)
        if (void* h = dlopen(c, RTLD_NOW | RTLD_LOCAL))
            return h;
    return nullptr;
}

// BCn, decision 3 above. Called from CreateInstance with an instance in hand and no
// device yet, which is the only window the patcher has.
void EnableBcnIfPatchable(VkInstance instance)
{
#if defined(CW_HAVE_ADRENOTOOLS)
    if (!g_table.vkEnumeratePhysicalDevices || !g_table.vkGetPhysicalDeviceProperties ||
        !g_table.vkGetPhysicalDeviceFormatProperties)
        return;

    uint32_t count = 0;
    if (g_table.vkEnumeratePhysicalDevices(instance, &count, nullptr) != VK_SUCCESS || !count)
        return;
    // One device is the norm and eight is absurd; anything past the cap is a driver bug
    // and reading it cannot make the BCn decision better.
    VkPhysicalDevice devices[8];
    if (count > 8)
        count = 8;
    if (g_table.vkEnumeratePhysicalDevices(instance, &count, devices) != VK_SUCCESS || !count)
        return;

    VkPhysicalDeviceProperties props{};
    g_table.vkGetPhysicalDeviceProperties(devices[0], &props);
    // Mesa packs major.minor.patch into VK_MAKE_VERSION; the proprietary Qualcomm driver
    // uses its own scheme, which is why the vendorId is part of the question rather than
    // an assertion about the version alone.
    const uint32_t major = VK_VERSION_MAJOR(props.driverVersion);
    const uint32_t minor = VK_VERSION_MINOR(props.driverVersion);
    const auto type = adrenotools_get_bcn_type(major, minor, props.vendorID);

    static const char* kTypeName[] = { "INCOMPATIBLE", "already supported (BLOB)",
                                       "PATCHABLE" };
    fprintf(stderr,
            "[vk-loader] GPU %s, driver %u.%u.%u (vendorID 0x%04X): BCn %s\n",
            props.deviceName, major, minor, VK_VERSION_PATCH(props.driverVersion),
            props.vendorID, kTypeName[int(type)]);

    if (type != ADRENOTOOLS_BCN_PATCH)
        return;

    // The patcher wants the function pointer IT will be intercepting, obtained through
    // GIPA — not our table slot, which after patching is the patched one. Asking for it
    // again is deliberate: adrenotools locates the driver's own implementation from the
    // pointer it is handed, and handing it a trampoline from a different loader instance
    // is the kind of thing that fails by corrupting a driver rather than by returning
    // false.
    void* fmtProps = reinterpret_cast<void*>(
        g_table.vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFormatProperties"));
    if (!fmtProps)
    {
        fprintf(stderr, "[vk-loader] BCn: vkGetPhysicalDeviceFormatProperties is not "
                        "resolvable — not patching.\n");
        return;
    }
    if (adrenotools_patch_bcn(fmtProps))
        fprintf(stderr, "[vk-loader] BCn PATCHED into this driver: DXT1/3/5 textures will "
                        "be reported as supported. This title uploads every texture as BC "
                        "(vk_renderer.cpp's textureCompressionBC requirement), so without "
                        "this the device creation below would fail on an Adreno whose "
                        "hardware decodes BC but whose driver does not advertise it.\n");
    else
        fprintf(stderr, "[vk-loader] BCn patch FAILED — the driver is now in an "
                        "UNDEFINED state per adrenotools' own contract. Expect device "
                        "creation to fail; re-run with CW_VK_NO_CUSTOM_DRIVER=1 to get "
                        "back to the vendor driver and confirm that is what happened.\n");
#else
    (void)instance;
#endif
}

}  // namespace

int InitFromLoader(void* loader)
{
    if (!loader)
        return 0;
    int resolved = 0;
#define CWVK_X(fn)                                                             \
    g_table.fn = reinterpret_cast<PFN_##fn>(dlsym(loader, #fn));               \
    if (g_table.fn)                                                            \
        ++resolved;
    CWVK_FUNCTION_LIST(CWVK_X)
#undef CWVK_X

    g_missingAfterPhase1 = FunctionCount() - resolved;
    // The table is only USABLE once the one function everything else is reached through
    // exists: phase 2 fills the gaps with vkGetInstanceProcAddr, so a loader that has
    // 94 of 95 and not that one is a loader we cannot use at all.
    g_ready = g_table.vkGetInstanceProcAddr != nullptr;
    fprintf(stderr, "[vk-loader] phase 1: %d of %d entry points resolved by dlsym\n",
            resolved, FunctionCount());
    return resolved;
}

void InitFromInstance(VkInstance instance)
{
    if (!instance || !g_table.vkGetInstanceProcAddr)
        return;
    int filled = 0, stillMissing = 0;
#define CWVK_X(fn)                                                                          \
    if (!g_table.fn)                                                                        \
    {                                                                                       \
        void* p = reinterpret_cast<void*>(g_table.vkGetInstanceProcAddr(instance, #fn));    \
        if (p)                                                                              \
        {                                                                                   \
            g_table.fn = reinterpret_cast<PFN_##fn>(p);                                     \
            ++filled;                                                                       \
        }                                                                                   \
        else                                                                                \
            ++stillMissing;                                                                 \
    }
    CWVK_FUNCTION_LIST(CWVK_X)
#undef CWVK_X

    if (filled)
        fprintf(stderr,
                "[vk-loader] phase 2: %d entry point%s the loader does not export as a "
                "symbol resolved through vkGetInstanceProcAddr (this loader predates "
                "Vulkan 1.3, API %d). %s\n",
                filled, filled == 1 ? "" : "s", android_get_device_api_level(),
                stillMissing ? "Some are STILL missing — see the next line." : "");
    if (stillMissing)
        ReportMissing();
}

VkResult CreateInstance(const VkInstanceCreateInfo* pCreateInfo,
                        const VkAllocationCallbacks* pAllocator, VkInstance* pInstance)
{
    if (!g_table.vkCreateInstance)
    {
        // Never reach here without a loader, but if we do, say which failure it is: a
        // null vkCreateInstance means InitLoader did not run or found nothing, and
        // VK_ERROR_INITIALIZATION_FAILED with a line in the log is a diagnosable
        // "no Vulkan on this device", where a segfault inside the loader trampoline
        // would be neither.
        fprintf(stderr, "[vk-loader] vkCreateInstance is not in the table — no loader was "
                        "opened. Is this device 64-bit with a Vulkan driver at all?\n");
        return VK_ERROR_INITIALIZATION_FAILED;
    }

    const VkResult rc = g_table.vkCreateInstance(pCreateInfo, pAllocator, pInstance);
    if (rc == VK_SUCCESS && pInstance && *pInstance)
    {
        InitFromInstance(*pInstance);
        EnableBcnIfPatchable(*pInstance);
    }
    else if (rc != VK_SUCCESS)
        fprintf(stderr, "[vk-loader] vkCreateInstance failed (%d) with the %s driver\n",
                int(rc), g_loaderSource);
    return rc;
}

int ReportMissing()
{
    int missing = 0;
#define CWVK_X(fn)                                                    \
    if (!g_table.fn)                                                  \
    {                                                                 \
        if (missing < 12)                                             \
            fprintf(stderr, "[vk-loader] MISSING entry point: %s\n", #fn); \
        ++missing;                                                    \
    }
    CWVK_FUNCTION_LIST(CWVK_X)
#undef CWVK_X
    if (missing > 12)
        fprintf(stderr, "[vk-loader] ... and %d more\n", missing - 12);
    if (missing)
        fprintf(stderr,
                "[vk-loader] %d of %d entry points this runtime calls are not available "
                "from %s. The renderer will fail at the first one it reaches; a device "
                "whose loader cannot answer vkGetInstanceProcAddr for a Vulkan 1.3 "
                "function is a device this port cannot run on.\n",
                missing, FunctionCount(), g_loaderSource);
    return missing;
}

bool Ready() { return g_ready; }

const char* LoaderSource() { return g_loaderSource; }

bool InitLoader()
{
    std::call_once(g_loaderOnce, [] {
        const DriverChoice choice = ReadDriverChoice();

#if defined(CW_HAVE_ADRENOTOOLS)
        if (choice.wanted)
        {
            const char* hookDir = HookDir();
            if (!hookDir)
            {
                fprintf(stderr, "[vk-loader] cannot determine the hook directory "
                                "(dladdr failed) — using the SYSTEM driver instead of "
                                "%s/%s.\n", choice.dir, choice.name);
            }
            else
            {
                // API < 29 has no memfd for adrenotools to patch libraries through, so it
                // needs a writable scratch directory; the launcher exports cacheDir.
                const char* tmpDir = android_get_device_api_level() >= 29
                                         ? nullptr : getenv("CW_VK_TMP_DIR");
                void* handle = adrenotools_open_libvulkan(
                    RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, tmpDir, hookDir, choice.dir,
                    choice.name, nullptr, nullptr);
                if (handle)
                {
                    g_loader = handle;
                    snprintf(g_loaderSource, sizeof g_loaderSource, "adrenotools:%s",
                             choice.name);
                    fprintf(stderr,
                            "[vk-loader] custom GPU driver: %s/%s (hooks %s)\n",
                            choice.dir, choice.name, hookDir);
                    // logcat as well as stderr: on a NON-debuggable build Android sends
                    // stderr to /dev/null, and "which driver did my phone actually use" is
                    // the first question of every performance report this port will get.
                    // The file tee in host/log_file.cpp covers the same ground for the
                    // in-app log viewer; this is for adb.
                    CW_LOG("GPU driver: %s/%s via adrenotools", choice.dir, choice.name);
                    // Maximum clocks, asked for once at boot rather than per frame.
                    // Thermal limits still apply — this is the KGSL power-control knob,
                    // not an overclock — and the alternative is a governor that ramps up
                    // only after the guest has already stalled waiting for a fence.
                    // CW_VK_TURBO=0 is the control arm.
                    const char* turbo = getenv("CW_VK_TURBO");
                    if (!turbo || !*turbo || *turbo != '0')
                    {
                        adrenotools_set_turbo(true);
                        fprintf(stderr, "[vk-loader] adrenotools turbo: ON "
                                        "(CW_VK_TURBO=0 is the control arm)\n");
                    }
                }
                else
                    fprintf(stderr,
                            "[vk-loader] adrenotools_open_libvulkan RETURNED NULL for "
                            "%s/%s — falling back to the system driver. Usual causes, in "
                            "order: the .so is not in that directory; the directory is on "
                            "shared storage (dlopen refuses it); the app was packaged "
                            "without extractNativeLibs/useLegacyPackaging, so the hook "
                            "libraries are still inside the APK and %s does not contain "
                            "them; or this is not an Adreno device.\n",
                            choice.dir, choice.name, hookDir);
            }
        }
        else if (getenv("CW_VK_DRIVER_DIR"))
            fprintf(stderr, "[vk-loader] CW_VK_NO_CUSTOM_DRIVER=1 — the system driver, by "
                            "request (this is the control arm for any \"only broken with "
                            "Turnip\" report).\n");
#else
        if (choice.wanted)
            fprintf(stderr,
                    "[vk-loader] a custom driver was requested (%s/%s) but THIS BUILD HAS "
                    "NO libadrenotools — using the system driver. Rebuild with "
                    "tools/android/build_adrenotools.sh having run; the CMake line that "
                    "sets CW_HAVE_ADRENOTOOLS says whether it did.\n",
                    choice.dir, choice.name);
#endif

        if (!g_loader)
        {
            g_loader = OpenSystemLoader();
            if (g_loader)
                snprintf(g_loaderSource, sizeof g_loaderSource, "system");
        }

        if (!g_loader)
        {
            // dlerror() is consumed by the read, so it is read ONCE into a variable: the
            // obvious spelling (`dlerror() ? dlerror() : "(none)"`) calls it twice and
            // prints "(none)" or a stale message for the failure it was asked about.
            const char* err = dlerror();
            fprintf(stderr,
                    "[vk-loader] NO VULKAN LOADER could be opened (tried adrenotools and "
                    "/system/lib64/libvulkan.so): %s\n"
                    "[vk-loader] This device has no Vulkan, or it is a 32-bit device whose "
                    "loader is not in /system/lib64. This port is 64-bit only.\n",
                    err ? err : "(no dlerror)");
            CW_LOG("no Vulkan loader could be opened: %s", err ? err : "(no dlerror)");
            snprintf(g_loaderSource, sizeof g_loaderSource, "none");
            return;
        }

        const int resolved = InitFromLoader(g_loader);
        if (!g_ready)
            fprintf(stderr,
                    "[vk-loader] the loader at %s has no vkGetInstanceProcAddr — it is not "
                    "a Vulkan loader.\n", g_loaderSource);
        else if (g_missingAfterPhase1)
            fprintf(stderr,
                    "[vk-loader] %d entry point%s not exported as symbols by this loader; "
                    "they are resolved through vkGetInstanceProcAddr once an instance "
                    "exists (Android loaders before API 33 do not export Vulkan 1.3).\n",
                    g_missingAfterPhase1, g_missingAfterPhase1 == 1 ? "" : "s");
        (void)resolved;
    });
    return g_ready;
}

}  // namespace CwVk

// =====================================================================================
// THE SYMBOLS SDL DLSYMS BY NAME.
//
// SDL_Vulkan_LoadLibrary(path) does not link anything: it dlopens `path` and dlsyms four
// names out of it — vkGetInstanceProcAddr, vkCreateInstance,
// vkEnumerateInstanceExtensionProperties and vkEnumerateInstanceVersion — and uses those
// for its own calls (SDL_Vulkan_CreateSurface resolves vkCreateAndroidSurfaceKHR through
// the first one). host/window.cpp points SDL at THIS library, so these four have to exist
// here as real exported symbols. They cannot be the shadowed names — CWVK_NO_SHADOW is
// defined above precisely so that `vkCreateInstance` in this file is a definition and not
// a rewrite of one — and they must forward to the same loader the renderer is using,
// because two loaders means two driver tables and one of them is not Turnip.
// =====================================================================================
extern "C"
{

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vkGetInstanceProcAddr(VkInstance instance,
                                                              const char* pName)
{
    if (!CwVk::g_table.vkGetInstanceProcAddr)
        CwVk::InitLoader();
    if (!CwVk::g_table.vkGetInstanceProcAddr)
        return nullptr;
    return CwVk::g_table.vkGetInstanceProcAddr(instance, pName);
}

VKAPI_ATTR VkResult VKAPI_CALL vkCreateInstance(const VkInstanceCreateInfo* pCreateInfo,
                                                const VkAllocationCallbacks* pAllocator,
                                                VkInstance* pInstance)
{
    if (!CwVk::Ready())
        CwVk::InitLoader();
    return CwVk::CreateInstance(pCreateInfo, pAllocator, pInstance);
}

VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceExtensionProperties(const char* pLayerName, uint32_t* pPropertyCount,
                                       VkExtensionProperties* pProperties)
{
    if (!CwVk::Ready())
        CwVk::InitLoader();
    if (!CwVk::g_table.vkEnumerateInstanceExtensionProperties)
        return VK_ERROR_INITIALIZATION_FAILED;
    return CwVk::g_table.vkEnumerateInstanceExtensionProperties(pLayerName, pPropertyCount,
                                                               pProperties);
}

VKAPI_ATTR VkResult VKAPI_CALL vkEnumerateInstanceVersion(uint32_t* pApiVersion)
{
    if (!CwVk::Ready())
        CwVk::InitLoader();
    // The function arrived in Vulkan 1.1; a 1.0 loader does not have it, and the correct
    // answer there is 1.0 rather than a failure — SDL treats a null as "assume 1.0" but
    // answers 1.0 itself if the symbol exists and says so.
    if (!CwVk::g_table.vkEnumerateInstanceVersion)
    {
        if (pApiVersion)
            *pApiVersion = VK_API_VERSION_1_0;
        return VK_SUCCESS;
    }
    return CwVk::g_table.vkEnumerateInstanceVersion(pApiVersion);
}

VKAPI_ATTR VkResult VKAPI_CALL
vkEnumerateInstanceLayerProperties(uint32_t* pPropertyCount, VkLayerProperties* pProperties)
{
    if (!CwVk::Ready())
        CwVk::InitLoader();
    if (!CwVk::g_table.vkEnumerateInstanceLayerProperties)
    {
        if (pPropertyCount)
            *pPropertyCount = 0;
        return VK_SUCCESS;
    }
    return CwVk::g_table.vkEnumerateInstanceLayerProperties(pPropertyCount, pProperties);
}

}  // extern "C"

#endif  // __ANDROID__
