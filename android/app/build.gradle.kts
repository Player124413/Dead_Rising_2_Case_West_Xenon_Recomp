// The app module. Everything unusual about it is unusual for a reason that is written down
// here, because the alternative is a build file that looks like a template and behaves like
// a puzzle: it produces ONE shared library from a CMake project that normally produces an
// executable, it links its three biggest dependencies statically, and it deliberately has no
// dependencies of its own.
//
// What this file does NOT do is decide anything about the runtime. Flags, sources and
// feature switches all live in ../runtime/CMakeLists.txt, which is the same file the desktop
// build uses; this file only tells it where the cross-compiled dependencies are and then
// packages whatever comes out.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------------------
// Properties, resolved once, with the fallbacks stated rather than implied.
// ---------------------------------------------------------------------------------------
//
// THE ${...} EXPANSION IS DONE HERE, ON PURPOSE, RATHER THAN LEFT TO GRADLE.
// gradle.properties is read as a flat java.util.Properties file and a value like
// `cw.sdl2Prefix=${cw.prebuiltRoot}/sdl2` is handed to a build script LITERALLY, placeholder and
// all. That is a particularly nasty failure, because nothing about it looks like a missing
// dependency: `file("${cw.prebuiltRoot}/sdl2")` resolves happily into a directory with the
// placeholder's braces in its name, the configure-time summary prints that path, and the error
// arrives from runtime/CMakeLists.txt as "SDL2 not found" for a prefix that visibly exists in
// gradle.properties. So the one thing that file wants to be able to say — "the same root as
// everything else, plus /sdl2" — is expanded by these three functions, along with ${env.NAME}
// and ${HOME}, which Gradle also does not expand.
private val PLACEHOLDER = Regex("""\$\{([A-Za-z0-9_.]+)\}""")

private fun rawProp(key: String): String? =
    providers.gradleProperty(key).orNull?.takeIf { it.isNotBlank() }

private fun expand(value: String, seen: Set<String> = emptySet()): String =
    PLACEHOLDER.replace(value) { m ->
        val name = m.groupValues[1]
        if (name in seen) error("gradle.properties: \${$name} refers to itself through $seen")
        when {
            name.startsWith("env.") -> System.getenv(name.removePrefix("env.")) ?: ""
            name == "HOME" -> System.getenv("HOME") ?: ""
            else -> rawProp(name)?.let { expand(it, seen + name) } ?: ""
        }
    }

fun prop(key: String, default: String = ""): String =
    rawProp(key)?.let { expand(it) } ?: default

val repoRoot = file(prop("cw.repoRoot", "..")).absoluteFile
val abi = prop("cw.abi", "arm64-v8a")
val prebuilt = file(prop("cw.prebuiltRoot", "../third_party/android/$abi")).absoluteFile

// An empty cw.xenonRoot means "the default runtime/CMakeLists.txt would have used" and is
// expanded HERE, where the answer can be printed rather than guessed at from a configure log.
// Both variables name a sibling checkout of a public repository; neither is optional, because
// the XEX loader and the shader translator are compiled into the runtime.
fun sibling(key: String, name: String): String {
    val given = prop(key)
    if (given.isNotEmpty()) return file(given).absolutePath
    val home = System.getenv("HOME") ?: error("\$HOME is not set; pass -P$key=/path/to/$name")
    return file("$home/GithubRepo/$name").absolutePath
}
val xenonRoot = sibling("cw.xenonRoot", "XenonRecomp")
val xenosRoot = sibling("cw.xenosRoot", "XenosRecomp")

// tools/android/build_xenon_android.sh cross-builds the three static libraries the runtime
// links into this tree, into a build directory named for the ABI. Empty means
// <XenonRecomp>/build-android-<abi>, which is the same default the script computes — and both
// name the ABI rather than hardcoding arm64, because a static library is not portable between
// architectures and an x86_64 emulator build has to land somewhere the arm64 one is not.
val xenonBuild = prop("cw.xenonBuild").ifEmpty { "$xenonRoot/build-android-$abi" }

// SDL's Java half has to be present for this module to COMPILE, so its absence is a
// configuration error rather than a build error 40 files deep: GameActivity extends
// SDLActivity, and "unresolved reference: SDLActivity" says nothing about the script that
// was supposed to copy it.
val sdlJavaDir = file(prop("cw.sdlJavaDir", "../third_party/android/sdl-java")).absoluteFile
if (!File(sdlJavaDir, "org/libsdl/app/SDLActivity.java").exists()) {
    throw GradleException(
        """
        No SDL Java sources at $sdlJavaDir.

        GameActivity extends org.libsdl.app.SDLActivity, so the .java half of SDL is part of
        this module's sources — and it must come from the SAME tree as the libSDL2.a being
        linked, because SDLActivity.onCreate compares its version constants against the
        library's nativeGetVersion() and refuses to start on a mismatch.

          tools/android/build_sdl2_android.sh ${prebuilt}/sdl2

        copies both: the static library into that prefix and the Java into $sdlJavaDir.
        Override with -Pcw.sdlJavaDir=/path/to/java if they live somewhere else.
        """.trimIndent()
    )
}

// The runtime's own assets (the gamepad-glyph chips, the prewarmed key list, the VS recipe
// table) are copied out of tools/release/ into a staging directory by build_all.sh. Their
// absence is a WARNING and not an error: the APK still builds and still installs, and the
// runtime's first-run gate reports exactly what is missing in words a player can act on —
// which is a better message than a build failure for something that is not a build problem.
// The shader cache is game-derived and is never staged by CI for the same reason the game
// itself is not: no runner may hold it (release-plan E.1).
val appAssetsDir = file(prop("cw.appAssets", "../third_party/android/app-assets")).absoluteFile
if (!File(appAssetsDir, "cw").exists()) {
    logger.warn(
        "cw: no runtime assets staged at $appAssetsDir/cw — the APK will build, and the " +
            "first-run gate will report the missing kbm_chips/prewarm.keys. Stage them with " +
            "tools/android/build_all.sh (it copies from tools/release/)."
    )
}

// libadrenotools: OPTIONAL, and the app is written for its absence. Without it the launcher
// lists only "system driver" and gpu/vk_shadow_android.cpp loads /system/lib64/libvulkan.so
// — which is a working port with no per-app Turnip. With it, the four hook libraries it
// needs in nativeLibraryDir come along as jniLibs, and that is the one part of this that
// cannot be discovered at run time: adrenotools dlopens them BY NAME from the app's own
// library directory, so an APK missing them fails at driver load with a message from inside
// the driver loader rather than one from us.
val adrenotoolsPrefix = file(prop("cw.adrenotoolsPrefix", "${prebuilt}/adrenotools")).absoluteFile
val adrenotoolsJniLibs =
    file(prop("cw.adrenotoolsJniLibs", "${prebuilt}/adrenotools/jniLibs")).absoluteFile
val haveAdrenotools = File(adrenotoolsPrefix, "include/adrenotools/driver.h").exists()

// DXC, from tools/android/build_dxc.sh. Also OPTIONAL at build time and also written for its
// absence, but the consequence differs in kind: an APK without adrenotools renders on the device's
// own Vulkan driver, while an APK without libdxcompiler.so cannot translate a single shader and so
// cannot draw at all. A warning rather than an error because the stub-image artifact CI builds on
// every pull request has no shaders to translate either, and failing that build over a library only
// a playable artifact needs would be the wrong trade.
val dxcJniLibs = file(prop("cw.dxcJniLibs", "${prebuilt}/dxc/jniLibs")).absoluteFile
val haveDxc = File(dxcJniLibs, "libdxcompiler.so").exists()

// Resolved here rather than inside nested string templates: `"${prop("a", "${b}/c")}"` is legal
// Kotlin and is also the kind of line that gets edited wrong, and the two prefixes are used twice
// each (once in the arguments, once in the configure-time summary below).
val sdl2Prefix = file(prop("cw.sdl2Prefix", "${prebuilt}/sdl2")).absolutePath
val ffmpegPrefix = file(prop("cw.ffmpegPrefix", "${prebuilt}/ffmpeg")).absolutePath
val ppcDir = prop("cw.ppcDir")

// The optional -D flags, as explicit typed lists. `listOf(...) + if (x) listOf(...) else
// emptyList()` relies on emptyList() inferring String from a context three operators away, and
// the failure it produces is a compile error in a build file, which is the least useful place in
// this project to have to think about type inference.
val optionalArgs: List<String> = buildList {
    if (ppcDir.isNotEmpty()) add("-DCW_PPC_DIR=${file(ppcDir).absolutePath}")
    if (haveAdrenotools) add("-DCW_ADRENOTOOLS_PREFIX=$adrenotoolsPrefix")
}
// The one combination that produces an APK which boots to a black screen: a REAL guest image with
// no compiler to translate its shaders. The stub image gets a pass because it never reaches a draw.
if (!haveDxc && ppcDir.isNotEmpty() && File(ppcDir, "ppc_func_mapping.cpp").exists()) {
    logger.warn(
        """
        cw: packaging a REAL guest image with no libdxcompiler.so at $dxcJniLibs.

        That APK boots, loads the title's own code, reaches the renderer, and then refuses every
        shader translation — the disc's 1,265 pixel shaders and all 104 vertex shaders alike. The
        screen stays black and the log says "[shxlate] no dxcompiler library found" once, which is
        easy to miss under a page of per-shader refusals.

        tools/android/build_dxc.sh $abi        (an LLVM build; no prebuilt arm64 DXC exists)
        or drop a libdxcompiler.so into        $dxcJniLibs
        """.trimIndent()
    )
}

if (!haveAdrenotools) {
    logger.warn(
        "cw: no libadrenotools at $adrenotoolsPrefix — building WITHOUT custom GPU driver " +
            "support (the launcher will say so). tools/android/build_adrenotools.sh " +
            "$adrenotoolsPrefix  (needs --recursive: its linkernsbypass submodule is a " +
            "source dependency)."
    )
} else if (!File(adrenotoolsJniLibs, "libmain_hook.so").exists()) {
    throw GradleException(
        """
        libadrenotools is at $adrenotoolsPrefix but its hook libraries are not at
        $adrenotoolsJniLibs (no libmain_hook.so).

        adrenotools dlopens libmain_hook.so and libhook_impl.so BY NAME out of the app's
        nativeLibraryDir. An APK with the static library linked and the hooks missing does
        not fail at build time or at startup — it fails when the player picks a driver, in
        a message from inside the driver loader. That is the defect this check exists to
        turn into a build failure. Re-run tools/android/build_adrenotools.sh; it stages all
        four hooks.
        """.trimIndent()
    )
}

android {
    namespace = "dev.casewest.android"
    compileSdk = 35
    ndkVersion = prop("cw.ndkVersion", "27.2.12479018")

    defaultConfig {
        applicationId = "dev.casewest.android"
        minSdk = 26
        targetSdk = 35
        versionCode = prop("cw.versionCode", "1").toInt()
        versionName = prop("cw.versionName", "0.1.0-android")

        // ONE ABI, and arm64-v8a is the only honest choice:
        //   * libadrenotools builds for arm64 only, so a second ABI would be a second app
        //     with a different feature set and one launcher UI that has to explain both;
        //   * every 64-bit phone sold is arm64. x86_64 Android is an emulator, and an
        //     emulator is a build-and-boot target, not a player — .github/workflows/android.yml
        //     builds one separately for exactly that, as its own ABI, and never ships it.
        // 32-bit armeabi-v7a is not built at all: this runtime's guest memory model wants a
        // 64-bit address space (kernel/memory.cpp maps three aliased views of the guest's
        // 512 MB physical space plus its 4 GB virtual one), and "all 64-bit phones" is the
        // port's stated target.
        ndk { abiFilters += abi }

        externalNativeBuild {
            cmake {
                // RelWithDebInfo for BOTH variants, including debug, and this is the single
                // most consequential line in the file. A Debug build of the recompiled image
                // is not something anyone can wait for: Fable 2 measured one at roughly one
                // movie frame per MINUTE, and Case West's image is 228 translation units of
                // the same generated VMX lowering. It is also not a measurement of anything —
                // every performance number this project has recorded was taken at -O2, so a
                // debug APK's frame rate would be a number no other number can be compared
                // to. What the debug variant is for is a debuggable app with a symbol-rich
                // library and no stripping, and RelWithDebInfo (-O2 -g) delivers that.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=RelWithDebInfo",
                    "-DXENON_ROOT=$xenonRoot",
                    // A SEPARATE BUILD TREE from the desktop's, and it has to be: the runtime
                    // links XenonUtils, fmt and xxhash as static libraries, so they must be
                    // compiled for arm64, while generating the guest image needs the HOST
                    // XenonRecomp executable. One `build/` directory cannot hold both — a
                    // cross-build into it would replace the tool that produced ppc/ with a
                    // binary the build machine cannot run.
                    "-DXENON_BUILD=$xenonBuild",
                    "-DXENOS_ROOT=$xenosRoot",
                    "-DCW_SDL2_PREFIX=$sdl2Prefix",
                    "-DCW_FFMPEG_PREFIX=$ffmpegPrefix",
                    // libxlive is a private sibling checkout: no CI runner has it, and the
                    // Android port does not want it either (achievements and co-op sessions
                    // are not what makes the title playable on a phone). OFF compiles
                    // kernel/xlive_stub.cpp instead, which answers every XLive export as if
                    // signed out. See the CW_XLIVE option in runtime/CMakeLists.txt.
                    "-DCW_XLIVE=OFF",
                ) + optionalArgs
            }
        }
    }

    externalNativeBuild {
        cmake {
            // The SAME CMakeLists.txt the desktop build configures. Not a copy, not an
            // Android-shaped variant: the source list, the feature switches and the flag
            // decisions are one file, and the only thing this adds is the toolchain AGP
            // hands it. That is what makes "it builds on Linux" evidence about "it builds
            // on Android" instead of a coincidence.
            path = File(repoRoot, "runtime/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            // SDL's Java, staged beside the libSDL2.a it must version-match.
            java.srcDir(sdlJavaDir)
            // The runtime's own assets, seeded into CW_ROOT on first launch.
            assets.srcDir(appAssetsDir)
            if (haveAdrenotools) {
                // adrenotools' four hook libraries. They are prebuilt, they are not ours to
                // compile, and they have to land in nativeLibraryDir as real extracted files
                // — which is what packaging.jniLibs.useLegacyPackaging below guarantees.
                jniLibs.srcDir(adrenotoolsJniLibs)
            }
            if (haveDxc) {
                // libdxcompiler.so. nativeLibraryDir IS HostPaths::ExeDir() on Android, and
                // ExeDir()/libdxcompiler.so is already one of gpu/shader_translator.cpp's dlopen
                // candidates — so packaging it here is the entire integration. No CW_DXC_LIB, no
                // launcher row, no code anywhere that knows this file exists.
                jniLibs.srcDir(dxcJniLibs)
            }
        }
    }

    packaging {
        jniLibs {
            // REQUIRED, and not a packaging preference. libadrenotools' own documentation
            // states it as a condition of working at all: it dlopens its hook libraries out
            // of applicationInfo.nativeLibraryDir, which is a directory of real files only
            // when the APK's libraries were extracted at install. With the modern default
            // (useLegacyPackaging=false) they stay compressed inside the APK and are mapped
            // straight out of it, nativeLibraryDir has nothing in it, and the custom driver
            // load fails on a device whose driver support is otherwise fine.
            //
            // The cost is install size: the libraries are stored uncompressed on the data
            // partition as well as in the APK. For this app that is libcw_runtime.so plus
            // libc++_shared.so plus four small hooks, and it is the same trade every
            // emulator that offers per-app GPU drivers has already made.
            useLegacyPackaging = true
        }
        resources {
            // SDL and ffmpeg both ship a LICENSE; two copies of a file with the same name
            // from different jars is a merge conflict this build has no opinion about.
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    signingConfigs {
        // A release keystore if the developer has one, named in gradle.properties or on the
        // command line and never in git. CI does not have one and does not pretend to: it
        // builds the debug variant, which is signed with the local debug key and installs
        // with `adb install` like any sideloaded APK.
        create("releaseIfConfigured") {
            val store = prop("cw.keystore")
            if (store.isNotEmpty()) {
                storeFile = file(store)
                storePassword = prop("cw.keystorePassword")
                keyAlias = prop("cw.keyAlias")
                keyPassword = prop("cw.keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Debuggable so `adb shell run-as` reaches CW_ROOT and a crash report's host pc
            // can be addr2line'd against the library in the APK. The symbols are already in
            // it: CW_SPLIT_DEBUG is forced off for Android in runtime/CMakeLists.txt because
            // Gradle does this job, with the NDK's own strip, and keeps the unstripped copy
            // under intermediates/merged_native_libs where a symbolication run looks.
            isDebuggable = true
            isJniDebuggable = false
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            val store = prop("cw.keystore")
            signingConfig = if (store.isNotEmpty()) {
                signingConfigs.getByName("releaseIfConfigured")
            } else {
                // Signed with the debug key rather than left unsigned, and this is a choice
                // about sideloading: an unsigned APK cannot be installed at all, and this
                // project's distribution is a file a player installs themselves. A store
                // build would need a real key, which is what cw.keystore is for.
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // No view binding and no data binding: the launcher's views are found with findViewById,
    // and the only "configuration" this app has is the environment it hands the runtime, which
    // lives in RuntimeConfig.kt where it can be read.
    //
    // BuildConfig IS generated, for exactly one field: whether libadrenotools was linked. That
    // is a property of the artifact and not of the device, so it cannot be discovered at run
    // time — and a launcher that offered a "custom driver" row in a build with no adrenotools
    // in it would be offering something that cannot work, with the failure arriving from inside
    // a driver loader nobody can read.
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("boolean", "HAVE_ADRENOTOOLS", haveAdrenotools.toString())
    }
}

dependencies {
    // Nothing. Not "nothing yet" — see android.useAndroidX in ../gradle.properties for the
    // reasoning. The Kotlin standard library comes from the plugin, SDL's Java is a source
    // directory, and every native dependency is linked into libcw_runtime.so by CMake.
}

// ---------------------------------------------------------------------------------------
// A configure-time summary, because this build has four external inputs and a failure in
// any one of them looks like a failure in the one below it.
// ---------------------------------------------------------------------------------------
logger.lifecycle(
    """
    cw android/$abi
      runtime CMake   ${File(repoRoot, "runtime/CMakeLists.txt")}
      XenonRecomp     $xenonRoot
      XenonRecomp build $xenonBuild   ($abi static libs; NOT the host tool's tree)
      XenosRecomp     $xenosRoot
      SDL2 (static)   $sdl2Prefix
      SDL Java        $sdlJavaDir
      ffmpeg (static) $ffmpegPrefix
      adrenotools     ${if (haveAdrenotools) adrenotoolsPrefix.toString() else "ABSENT — no custom GPU drivers"}
      DXC               ${if (haveDxc) "libdxcompiler.so from $dxcJniLibs" else "ABSENT — no shader translation on the device"}
      guest image     ${if (ppcDir.isNotEmpty()) ppcDir else "sibling ppc/ (default)"}
      extra cmake     ${optionalArgs.joinToString(" ").ifEmpty { "(none)"}}
    """.trimIndent()
)

// A local.properties in this directory is a developer's machine and is not in git; the
// Android SDK location may also come from ANDROID_HOME/ANDROID_SDK_ROOT, which is what CI
// uses. This exists so that "SDK location not found" names a file that can be written.
val localProperties = File(rootDir, "local.properties")
if (!localProperties.exists() && System.getenv("ANDROID_HOME") == null &&
    System.getenv("ANDROID_SDK_ROOT") == null
) {
    logger.warn(
        "cw: no android/local.properties and no ANDROID_HOME/ANDROID_SDK_ROOT. Write " +
            "sdk.dir=/path/to/Android/sdk into $localProperties, or export ANDROID_HOME."
    )
}
