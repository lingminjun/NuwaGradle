package cn.jiajixin.nuwa

import cn.jiajixin.nuwa.util.*
import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project

class NuwaPlugin implements Plugin<Project> {
    HashSet<String> includePackage
    HashSet<String> excludeClass
    def debugOn
    def patchList = []
    def beforeDexTasks = []
    private static final String NUWA_DIR = "NuwaDir"
    private static final String NUWA_PATCHES = "nuwaPatches"

    private static final String MAPPING_TXT = "mapping.txt"
    private static final String HASH_TXT = "hash.txt"

    private static final String DEBUG = "debug"

    def poutFile(out) {
        if (out != null) {
            println new File(out).text
        }
    }

    @Override
    void apply(Project project) {

        project.extensions.create("nuwa", NuwaExtension, project)

        project.afterEvaluate {
            def extension = project.extensions.findByName("nuwa") as NuwaExtension
            includePackage = extension.includePackage
            excludeClass = extension.excludeClass
            debugOn = extension.debugOn

            project.android.applicationVariants.each { variant ->

                if (!variant.name.contains(DEBUG) || (variant.name.contains(DEBUG) && debugOn)) {

                    Map hashMap
                    File nuwaDir
                    File patchDir

                    def preDexTask = project.tasks.findByName("preDex${variant.name.capitalize()}")
                    if (preDexTask == null) {
                        System.out.println("未找到其task:preDex${variant.name.capitalize()}");
//                        return ;
                    } else {
                        System.out.println("找到其task:preDex${variant.name.capitalize()}");
                    }
                    def dexTask = project.tasks.findByName("dex${variant.name.capitalize()}")
                    if (dexTask == null) {
                        System.out.println("未找到其task:dex${variant.name.capitalize()}");
                    } else {
                        System.out.println("找到其task:dex${variant.name.capitalize()}");
                    }

                    //1.5.0以上隐藏dex和proguard task，所以也是找不到对应的task
                    def proguardTask = project.tasks.findByName("proguard${variant.name.capitalize()}")

                    def processManifestTaskName = "process${variant.name.capitalize()}Manifest";
                    def processManifestTask = project.tasks.findByName(processManifestTaskName)
                    def manifestFile1 = processManifestTask.outputs.files.files[0];
                    def manifestFile = variant.outputs.processManifest.manifestOutputFile[0]

                    //查看此时是否跑的Android Studio环境instant-run
                    if (manifestFile == null) {
                        System.out.println("找不到ManifestTask，停止nuwa hack!");
                        return ;
                    } else if (processManifestTaskName.toLowerCase().contains("instantrun") || processManifestTaskName.toLowerCase().contains("instant-run")) {
                        System.out.println("开启instant run，无法有效执行nuwa hack1! " + processManifestTaskName);
//                        return ;
                    } else if (manifestFile.absolutePath.toLowerCase().contains("instant-run")) {
                        System.out.println("开启instant run，无法有效执行nuwa hack2!! " + manifestFile.absolutePath);
//                        return ;
                    } else if (manifestFile1.absolutePath.toLowerCase().contains("instant-run")) {
                        System.out.println("instant run - manifest " + manifestFile1.absolutePath);
                    }

                    if (manifestFile != null && !manifestFile.absolutePath.equals(manifestFile1.absolutePath)) {
                        poutFile(manifestFile.absolutePath);
                        poutFile(manifestFile1.absolutePath);
                    }

                    def oldNuwaDir = NuwaFileUtils.getFileFromProperty(project, NUWA_DIR)
                    if (oldNuwaDir) {
                        def mappingFile = NuwaFileUtils.getVariantFile(oldNuwaDir, variant, MAPPING_TXT)
                        NuwaAndroidUtils.applymapping(proguardTask, mappingFile)
                    }
                    if (oldNuwaDir) {
                        def hashFile = NuwaFileUtils.getVariantFile(oldNuwaDir, variant, HASH_TXT)
                        hashMap = NuwaMapUtils.parseMap(hashFile)
                    }

                    def dirName = variant.dirName
                    nuwaDir = new File("${project.buildDir}/outputs/nuwa")
                    def outputDir = new File("${nuwaDir}/${dirName}")
                    def hashFile = new File(outputDir, "hash.txt")

                    Closure nuwaPrepareClosure = {
                        def applicationName = NuwaAndroidUtils.getApplication(manifestFile)
                        if (applicationName != null) {
                            excludeClass.add(applicationName)
                        }

                        outputDir.mkdirs()
                        if (!hashFile.exists()) {
                            hashFile.createNewFile()
                        }

                        if (oldNuwaDir) {
                            patchDir = new File("${nuwaDir}/${dirName}/patch")
                            patchDir.mkdirs()
                            patchList.add(patchDir)
                        }
                    }

                    def nuwaPatch = "nuwa${variant.name.capitalize()}Patch"
                    project.task(nuwaPatch) << {
                        if (patchDir) {
                            NuwaAndroidUtils.dex(project, patchDir)
                        }
                    }
                    def nuwaPatchTask = project.tasks[nuwaPatch]

                    Closure copyMappingClosure = {
                        def mapFile = new File("${project.buildDir}/outputs/mapping/${variant.dirName}/mapping.txt")
                        if (mapFile.exists()) {
                            def newMapFile = new File("${nuwaDir}/${variant.dirName}/mapping.txt");
                            FileUtils.copyFile(mapFile, newMapFile)
                            System.out.println("拷贝mapping:${mapFile.absolutePath}");
                        }
                    }

                    if (preDexTask) {
                        def nuwaJarBeforePreDex = "nuwaJarBeforePreDex${variant.name.capitalize()}"
                        //增加一个串改.class的task【nuwaJarBeforePreDex】 主要在类中添加referHack
                        project.task(nuwaJarBeforePreDex) << {
                            Set<File> inputFiles = preDexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (NuwaProcessor.shouldProcessPreDexJar(path)) {
                                    NuwaProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }

                        //获取上面创建的task(nuwaJarBeforePreDex)
                        def nuwaJarBeforePreDexTask = project.tasks[nuwaJarBeforePreDex]

                        //关联到preDexTask上
                        nuwaJarBeforePreDexTask.dependsOn preDexTask.taskDependencies.getDependencies(preDexTask)
                        preDexTask.dependsOn nuwaJarBeforePreDexTask

                        nuwaJarBeforePreDexTask.doFirst(nuwaPrepareClosure)

                        def nuwaClassBeforeDex = "nuwaClassBeforeDex${variant.name.capitalize()}"
                        project.task(nuwaClassBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                                    if (NuwaSetUtils.isIncluded(path, includePackage)) {
                                        if (!NuwaSetUtils.isExcluded(path, excludeClass)) {
                                            def bytes = NuwaProcessor.processClass(inputFile)
                                            path = path.split("${dirName}/")[1]
                                            def hash = DigestUtils.shaHex(bytes)
                                            hashFile.append(NuwaMapUtils.format(path, hash))

                                            if (NuwaMapUtils.notSame(hashMap, path, hash)) {
                                                NuwaFileUtils.copyBytesToFile(inputFile.bytes, NuwaFileUtils.touchFile(patchDir, path))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        def nuwaClassBeforeDexTask = project.tasks[nuwaClassBeforeDex]
                        nuwaClassBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        dexTask.dependsOn nuwaClassBeforeDexTask

                        nuwaClassBeforeDexTask.doLast(copyMappingClosure)

                        nuwaPatchTask.dependsOn nuwaClassBeforeDexTask
                        beforeDexTasks.add(nuwaClassBeforeDexTask)
                    } else {

                        if (dexTask == null) {
                            dexTask = project.tasks.findByName("transformClassesWithDexFor${variant.name.capitalize()}")
                            if (dexTask == null) {
                                System.out.println("未找到其task:transformClassesWithDexFor${variant.name.capitalize()}");
                                return ;
                            }
                            System.out.println("找到其task:transformClassesWithDexFor${variant.name.capitalize()}");
                        }

                        def nuwaJarBeforeDex = "nuwaJarBeforeDex${variant.name.capitalize()}"
                        //增加一个串改.class的task【nuwaJarBeforePreDex】 主要在类中添加referHack
                        project.task(nuwaJarBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                System.out.println("check:${path}");//check:/Users/lingminjun/work/work_code/plugin_work/Nuwa/sample/build/intermediates/transforms/proguard/qihoo/debug
                                if (!path.contains("com.android.") && !path.contains("com/android/") && !path.contains("/android/m2repository")) {
                                    if (path.endsWith(".jar")) {
                                        NuwaProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                    } else if (inputFile.isDirectory()) {//是目录，重复此方法
                                        System.out.println("nuwa refer hack dir:${path}");
                                        NuwaProcessor.processDirPath(dirName, hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                    } else {
                                        System.out.println("nuwa refer hack failed:${path}");
                                    }
                                }
                            }
                        }
                        def nuwaJarBeforeDexTask = project.tasks[nuwaJarBeforeDex]
                        //before前执行 dexTask的depends
                        nuwaJarBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        //然后将before插入到dexTask的前面
                        dexTask.dependsOn nuwaJarBeforeDexTask

                        nuwaJarBeforeDexTask.doFirst(nuwaPrepareClosure)
                        nuwaJarBeforeDexTask.doLast(copyMappingClosure)

                        nuwaPatchTask.dependsOn nuwaJarBeforeDexTask
                        beforeDexTasks.add(nuwaJarBeforeDexTask)
                    }
                }
            }

            project.task(NUWA_PATCHES) << {
                patchList.each { patchDir ->
                    NuwaAndroidUtils.dex(project, patchDir)
                }
            }
            beforeDexTasks.each {
                project.tasks[NUWA_PATCHES].dependsOn it
            }
        }
    }
}


