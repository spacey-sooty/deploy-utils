package jaci.gradle.nativedeps

import jaci.gradle.EmbeddedTools
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.language.nativeplatform.DependentSourceSet
import org.gradle.language.nativeplatform.tasks.AbstractNativeCompileTask
import org.gradle.model.Defaults
import org.gradle.model.Each
import org.gradle.model.Model
import org.gradle.model.ModelMap
import org.gradle.model.Mutate
import org.gradle.model.RuleSource
import org.gradle.nativeplatform.*
import org.gradle.nativeplatform.platform.NativePlatform
import org.gradle.nativeplatform.tasks.AbstractLinkTask
import org.gradle.platform.base.BinaryContainer
import org.gradle.platform.base.BinarySpec
import org.gradle.platform.base.BinaryTasks
import org.gradle.platform.base.PlatformContainer

class NativeDepsPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) { }

    static class NativeDepsRules extends RuleSource {
        @Model("libraries")
        void createLibrariesModel(NativeDepsSpec spec) { }

        @Defaults
        void setDefaultCombined(@Each CombinedNativeLib combined) {
            combined.setLibs([] as List<String>)
        }

        @Mutate
        void addMavenDeps(final NativeDepsSpec spec, final ExtensionContainer extensions) {
            Project project = extensions.getByType(EmbeddedTools.ProjectWrapper).project

            // Add Maven Dependencies if necessary
            spec.withType(NativeLib) {
                if (it.getMaven() != null) {
                    def cfg = project.configurations.maybeCreate("native_${it.name}")
                    project.dependencies.add(cfg.name, it.getMaven())
                }
            }
        }

        @Mutate
        void addNativeLibraries(ModelMap<Task> tasks, final Repositories repos,
                                final NativeDepsSpec spec, final ExtensionContainer extensions,
                                final FlavorContainer flavors, final BuildTypeContainer buildTypes, final PlatformContainer platforms) {

            // Add the library bindings, so we can access them from the native component configuration.
            // This must be different from addMavenDeps since by now, the dependencies of the project are 'locked'
            // and set as read-only.

            PrebuiltLibraries prelibs = repos.maybeCreate('embeddedTools', PrebuiltLibraries)
            Project project = extensions.getByType(EmbeddedTools.ProjectWrapper).project

            spec.withType(NativeLib).each { lib ->
                def libname = lib.name
                FileTree rootTree, sharedFiles, staticFiles

                def flavor = flavors.getByName(lib.flavor ?: flavors.first().name)
                def buildType = buildTypes.getByName(lib.buildType ?: buildTypes.first().name)
                def platform = platforms.getByName(lib.targetPlatform) as NativePlatform

                if (lib.getMaven() != null) {
                    def cfg = project.configurations.getByName("native_${libname}")

                    rootTree = project.zipTree(cfg.dependencies.collectMany { cfg.files(it) }.first())
                } else if (lib.getFile().isDirectory()) {
                    rootTree = project.fileTree(lib.getFile())
                } else {
                    // Assume ZIP File
                    rootTree = project.zipTree(lib.getFile())
                }

                sharedFiles = rootTree.matching { pat -> pat.include(lib.sharedMatchers) }
                staticFiles = rootTree.matching { pat -> pat.include(lib.staticMatchers) }

                Set<File> headerDirs = lib.headerDirs.collect { new File(rootTree.asFileTrees.first().dir, it) }
                FileCollection headerFiles = project.files(headerDirs)
                prelibs.create(libname) { PrebuiltLibrary pl ->
                    List<FileCollection> linkerLibs = lib.addLinkerArgs ? [staticFiles, sharedFiles] : []
                    NativeLibBinary natLib = new NativeLibBinary(pl.name, headerFiles, staticFiles + sharedFiles, sharedFiles, platform, flavor, buildType, linkerLibs)
                    pl.binaries.add(natLib)
                    pl.headers.srcDirs.addAll(headerDirs)
                }
            }

            spec.withType(CombinedNativeLib).each { lib ->
                def libs = lib.libs.collect { prelibs.getByName(it) }

                def binaries = libs.collect { it.binaries.first() as NativeLibBinary }
                def linkFiles = binaries.collect { it.linkFiles }.inject { a, b -> a+b }
                def runtimeFiles = binaries.collect { it.runtimeFiles }.inject { a, b -> a+b }
                def headerFiles = binaries.collect { it.headerDirs }.inject { a, b -> a+b }
                def linkerLibs = binaries.collect { it.linkerLibs }.inject { a, b -> a+b}

                def flavor = flavors.getByName(lib.flavor ?: flavors.first().name)
                def buildType = buildTypes.getByName(lib.buildType ?: buildTypes.first().name)
                def platform = platforms.getByName(lib.targetPlatform) as NativePlatform

                prelibs.create(lib.name) { PrebuiltLibrary pl ->
                    NativeLibBinary natLib = new NativeLibBinary(pl.name, headerFiles, linkFiles, runtimeFiles, platform, flavor, buildType, linkerLibs)
                    pl.binaries.add(natLib)
                    libs.each { pl.headers.srcDirs.addAll(it.headers.srcDirs) }
                }
            }
        }

        @BinaryTasks
        void addLinkerArgs(ModelMap<Task> tasks, final Repositories repos, final NativeBinarySpec bin) {
            // Add the linker args (-L) for those that have been configured as such. This has to be done here
            // since we're interacting with the binary linking tasks, and as such, the repositories and libraries
            // must be locked to read only by now, else we get a cyclic dependency
            bin.inputs.withType(DependentSourceSet) { ss ->
                ss.libs.each { lss ->
                    if (lss instanceof LinkedHashMap) {
                        def lib = lss['library'] as String
                        tasks.withType(AbstractLinkTask) { task ->
                            task.doFirst() {
                                def nl = (repos.getByName('embeddedTools') as PrebuiltLibraries).getByName(lib).binaries.first()
                                if (nl instanceof NativeLibBinary) {
                                    def natLib = nl as NativeLibBinary
                                    natLib.linkerLibs.each { fc ->
                                        bin.linker.args(*(fc.files.collect {
                                            it.parentFile as File
                                        }.unique().collectMany {
                                            ["-L", it.absolutePath]
                                        }))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
