/*
 * Copyright (c) 2020, Dropbox, Inc. All rights reserved.
 */

package com.dropbox.affectedmoduledetector

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.impldep.org.jetbrains.annotations.VisibleForTesting

/**
 * This plugin creates and registers all affected test tasks.
 * Advantage is speed in not needing to skip modules at a large scale.
 *
 * Registers 3 tasks:
 *     - `gradlew runAffectedUnitTests` - runs jvm tests
 *     - `gradlew runAffectedAndroidTests` - runs connected tests
 *     - `gradlew assembleAffectedAndroidTests` - assembles but does not run on device tests,
 * useful when working with device labs.
 *
 * Configure using affected module detector block after applying the plugin:
 *
 *   affectedModuleDetector {
 *       baseDir = "${project.rootDir}"
 *       pathsAffectingAllModules = [
 *           "buildSrc/"
 *       ]
 *       logFolder = "${project.rootDir}".
 *   }
 *
 * To enable affected module detection, you need to pass [ENABLE_ARG]
 * into the build as a command line parameter.
 *
 * See [AffectedModuleDetector] for additional flags.
 */
class AffectedModuleDetectorPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        require(
            value = project.isRoot,
            lazyMessage = {
                "Must be applied to root project, but was found on ${project.path} instead."
            }
        )

        registerSubprojectConfiguration(project)
        registerMainConfiguration(project)
        registerCustomTasks(project)
        registerTestTasks(project)

        project.gradle.projectsEvaluated {
            AffectedModuleDetector.configure(project.gradle, project)

            filterAndroidTests(project)
            filterJvmTests(project)
            filterCustomTasks(project)
        }
    }

    private fun registerMainConfiguration(project: Project) {
        project.extensions.add(
            AffectedModuleConfiguration.name,
            AffectedModuleConfiguration()
        )
    }

    private fun registerSubprojectConfiguration(project: Project) {
        project.subprojects { subproject ->
            subproject.extensions.add(
                AffectedTestConfiguration.name,
                AffectedTestConfiguration()
            )
        }
    }

    @VisibleForTesting
    internal fun registerCustomTasks(rootProject: Project) {
        val mainConfiguration = requireNotNull(
            value = rootProject.extensions.findByName(AffectedModuleConfiguration.name),
            lazyMessage = {  "Unable to find ${AffectedTestConfiguration.name} in $rootProject" }
        ) as AffectedModuleConfiguration

        mainConfiguration
            .customTasks
            .forEach { taskType ->
                registerImpactAnalysisTask(
                    rootProject = rootProject,
                    taskType = taskType,
                    groupName = CUSTOM_TASK_GROUP_NAME
                )
            }
    }

    @VisibleForTesting
    internal fun registerTestTasks(rootProject: Project) {
        registerImpactAnalysisTask(
            rootProject = rootProject,
            taskType = InternalTaskType.JVM_TEST,
            groupName = TEST_TASK_GROUP_NAME
        )

        registerImpactAnalysisTask(
            rootProject = rootProject,
            taskType = InternalTaskType.ANDROID_TEST,
            groupName = TEST_TASK_GROUP_NAME
        )

        registerImpactAnalysisTask(
            rootProject = rootProject,
            taskType = InternalTaskType.ASSEMBLE_ANDROID_TEST,
            groupName = TEST_TASK_GROUP_NAME
        )
    }

    @VisibleForTesting
    internal fun registerImpactAnalysisTask(
        rootProject: Project,
        taskType: AffectedModuleTaskType,
        groupName: String
    ) {
        val task = rootProject.tasks.register(taskType.commandByImpact).get()
        task.group = groupName
        task.description = taskType.taskDescription

        rootProject.subprojects { project ->
            project.afterEvaluate {
                pluginIds.forEach { pluginId ->
                    if (pluginId == PLUGIN_JAVA_LIBRARY || pluginId == PLUGIN_KOTLIN) {
                        if (taskType == InternalTaskType.JVM_TEST) {
                            withPlugin(pluginId, task, taskType, project)
                        }
                    } else {
                        withPlugin(pluginId, task, taskType, project)
                    }
                }
            }
        }
    }

    private fun withPlugin(
        pluginId: String,
        task: Task,
        testType: AffectedModuleTaskType,
        project: Project
    ) {
        project.pluginManager.withPlugin(pluginId) {
            getAffectedPath(testType, project)?.let { path ->
                if (AffectedModuleDetector.isProjectProvided(project)) {
                    task.dependsOn(path)
                }

                project.afterEvaluate {
                    project.tasks.findByPath(path)?.onlyIf {
                        AffectedModuleDetector.isProjectAffected(project)
                    }
                }
            }
        }
    }

    private fun getAffectedPath(
        taskType: AffectedModuleTaskType,
        project: Project
    ): String? {
        val tasks = requireNotNull(
            value = project.extensions.findByName(AffectedTestConfiguration.name),
            lazyMessage = { "Unable to find ${AffectedTestConfiguration.name} in $project" }
        ) as AffectedTestConfiguration

        return when (taskType) {
            InternalTaskType.ANDROID_TEST -> {
                getPathAndTask(project, tasks.runAndroidTestTask)
            }
            InternalTaskType.ASSEMBLE_ANDROID_TEST -> {
                getPathAndTask(project, tasks.assembleAndroidTestTask)
            }
            InternalTaskType.JVM_TEST -> {
                getPathAndTask(project, tasks.jvmTestTask)
            }
            else -> {
                getPathAndTask(project, taskType.originalGradleCommand)
            }
        }
    }

    private fun getPathAndTask(project: Project, task: String?): String? {
        return if (task.isNullOrBlank()) null else "${project.path}:${task}"
    }

    private fun filterAndroidTests(project: Project) {
        val tracker = DependencyTracker(project, null)
        project.tasks.configureEach { task ->
            if (task.name.contains("AndroidTest")) {
                tracker.findAllDependents(project).forEach { dependentProject ->
                    dependentProject.tasks.forEach { dependentTask ->
                        AffectedModuleDetector.configureTaskGuard(dependentTask)
                    }
                }
                AffectedModuleDetector.configureTaskGuard(task)
            }
        }
    }

    private fun filterCustomTasks(project: Project) {
        project.tasks.configureEach { task ->
            if (task.group == CUSTOM_TASK_GROUP_NAME) {
                AffectedModuleDetector.configureTaskGuard(task)
            }
        }
    }

    // Only allow unit tests to run if the AffectedModuleDetector says to include them
    private fun filterJvmTests(project: Project) {
        project.tasks.withType(Test::class.java).configureEach { task ->
            AffectedModuleDetector.configureTaskGuard(task)
        }
    }

    companion object {

        @VisibleForTesting
        internal const val TEST_TASK_GROUP_NAME = "Affected Module Detector"
        @VisibleForTesting
        internal const val CUSTOM_TASK_GROUP_NAME = "Affected Module Detector custom tasks"

        private const val PLUGIN_ANDROID_APPLICATION = "com.android.application"
        private const val PLUGIN_ANDROID_LIBRARY = "java-library"
        private const val PLUGIN_JAVA_LIBRARY = "com.android.library"
        private const val PLUGIN_KOTLIN = "kotlin"

        private val pluginIds = listOf(
            PLUGIN_ANDROID_APPLICATION,
            PLUGIN_ANDROID_LIBRARY,
            PLUGIN_JAVA_LIBRARY,
            PLUGIN_KOTLIN
        )
    }
}
