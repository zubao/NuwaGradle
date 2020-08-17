package cn.jiajixin.nuwa

import cn.jiajixin.nuwa.util.*
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.apache.commons.codec.digest.DigestUtils

import org.apache.commons.io.FileUtils


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

                    println variant.name.capitalize()

                    Map hashMap
                    File nuwaDir
                    File patchDir

                    def t1Name                  = "transformClassesAndResourcesWithR8For${variant.name.capitalize()}"
                    def transformClassesTask    = project.tasks.findByName(t1Name)
                    def t2Name              = "transformClassesAndDexWithShrinkResFor${variant.name.capitalize()}"
                    def transformClassedAndDexWithShrinkResTask = project.tasks.findByName(t2Name)

                    def processManifestTask = project.tasks.findByName("process${variant.name.capitalize()}Manifest")
                    def manifestFile        = new File("${processManifestTask.outputs.files.files[0].absolutePath}/AndroidManifest.xml")

                    def oldNuwaDir = NuwaFileUtils.getFileFromProperty(project, NUWA_DIR)

                    println "oldNuwaDir : ${oldNuwaDir}"

                    // 指定使用上次生成的mapping文件可以用个proguard文件来配置，不需要用插件
//                    if (oldNuwaDir) {
//                        def mappingFile = NuwaFileUtils.getVariantFile(oldNuwaDir, variant, MAPPING_TXT)
//                        NuwaAndroidUtils.applymapping(proguardTask, mappingFile)
//                    }

                    if (oldNuwaDir) {
                        def oldHashFile = NuwaFileUtils.getVariantFile(oldNuwaDir, variant, HASH_TXT)
                        hashMap = NuwaMapUtils.parseMap(oldHashFile)
                    }

                    def dirName = variant.dirName
                    nuwaDir = new File("${project.buildDir}/outputs/nuwa")
                    def outputDir = new File("${nuwaDir}/${dirName}")
                    def hashFile = new File(outputDir, "hash.txt")
                    if (hashFile.exists()) {
                        hashFile.delete()
                    }

                    Closure nuwaPrepareClosure = {
                        def applicationName = NuwaAndroidUtils.getApplication(manifestFile)
                        println ">>>>>>> manifest applicationName ${applicationName}"
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
                    project.task(nuwaPatch)  {
                        if (patchDir) {
                            NuwaAndroidUtils.dex(project, patchDir)
                        }
                    }
                    def nuwaPatchTask = project.tasks[nuwaPatch]

                    Closure copyMappingClosure = {
                        def mapFile = new File("${project.buildDir}/outputs/mapping/${variant.dirName}/mapping.txt")
                        if(mapFile  && mapFile.exists()){
                            def newMapFile = new File("${nuwaDir}/${variant.dirName}/mapping.txt")
                            FileUtils.copyFile(mapFile, newMapFile)

                            FileUtils.copyFile(mapFile, NuwaFileUtils.getVariantFile(oldNuwaDir, variant, MAPPING_TXT))
                        }

                        if(hashFile  && hashFile.exists()){
                            FileUtils.copyFile(hashFile, NuwaFileUtils.getVariantFile(oldNuwaDir, variant, HASH_TXT))
                        }
                    }
                    transformClassedAndDexWithShrinkResTask.doLast(copyMappingClosure)


                    def nuwaJarBeforeDex = "nuwaJarBeforeDex${variant.name.capitalize()}"
                    project.task(nuwaJarBeforeDex)  {
                        doLast{
                            Set<File> inputFiles = transformClassesTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                println ">>>>>>>>> nuwaJarBeforeDex File: ${path} "
                                if (path.endsWith(".jar")) {
    //                                NuwaProcessor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }else if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                                    if (NuwaSetUtils.isIncluded(path, includePackage)) {
                                        if (!NuwaSetUtils.isExcluded(path, excludeClass)) {
                                            def bytes = NuwaProcessor.processClass(inputFile)
                                            path = path.split("classes/")[1]
                                            def hash = DigestUtils.shaHex(bytes)
                                            hashFile.append(NuwaMapUtils.format(path, hash))

                                            println(">>>>>>>>>> hash ${path} ${hash}")

                                            if (NuwaMapUtils.notSame(hashMap, path, hash)) {
                                                println ">>>>>> copyBytesToFile ${path}"
                                                NuwaFileUtils.copyBytesToFile(inputFile.bytes, NuwaFileUtils.touchFile(patchDir, path))
                                            }
                                        }
                                    }
                                }

                            }

                            // 找到变化的类之后打包为jar包
                            NuwaAndroidUtils.dex(project, patchDir)
                        }
                    }

                    def nuwaJarBeforeDexTask = project.tasks[nuwaJarBeforeDex]
                    nuwaJarBeforeDexTask.dependsOn transformClassesTask.taskDependencies.getDependencies(transformClassesTask)
                    transformClassesTask.dependsOn nuwaJarBeforeDexTask

                    nuwaJarBeforeDexTask.doFirst(nuwaPrepareClosure)

                    // 没有任务以来它不会自动执行
                    nuwaPatchTask.dependsOn nuwaJarBeforeDexTask
                    beforeDexTasks.add(nuwaJarBeforeDexTask)
                }
            }

            // 没有任务依赖它，不会自动执行
            project.task(NUWA_PATCHES)  {
                println ">>>>>>>>> dex 1 "
                patchList.each { patchDir ->
                    println ">>>>>>>>> dex 2 ${patchDir}"
                    NuwaAndroidUtils.dex(project, patchDir)
                }
            }
            beforeDexTasks.each {
                project.tasks[NUWA_PATCHES].dependsOn it
            }
        }
    }
}


