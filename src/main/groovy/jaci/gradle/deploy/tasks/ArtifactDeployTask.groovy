package jaci.gradle.deploy.tasks

import groovy.transform.CompileStatic
import jaci.gradle.WorkerStorage
import jaci.gradle.deploy.DeployContext
import jaci.gradle.deploy.artifact.ArtifactBase
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.IsolationMode
import org.gradle.workers.WorkerConfiguration
import org.gradle.workers.WorkerExecutor

import javax.inject.Inject

@CompileStatic
class ArtifactDeployTask extends DefaultTask {

    private static class DeployStorage {
        Project project
        DeployContext ctx
        ArtifactBase artifact

        DeployStorage(Project project, DeployContext ctx, ArtifactBase artifact) {
            this.project = project
            this.ctx = ctx
            this.artifact = artifact
        }
    }

    final WorkerExecutor workerExecutor
    static WorkerStorage<DeployStorage> deployerStorage  = WorkerStorage.obtain()

    @Input
    ArtifactBase artifact

    @Inject
    ArtifactDeployTask(WorkerExecutor workerExecutor) {
        this.workerExecutor = workerExecutor
    }

    @TaskAction
    void deployArtifact() {
        def discoveries = dependsOn.findAll {
            i -> i instanceof TargetDiscoveryTask && (i as TargetDiscoveryTask).isTargetActive()
        }.collect {
            it as TargetDiscoveryTask
        }

        artifact.taskDependencies = taskDependencies.getDependencies(this) as Set<Task>

        discoveries.each { TargetDiscoveryTask discover ->
            def index = deployerStorage.put(new DeployStorage(project, discover.context, artifact))
            workerExecutor.submit(DeployArtifactWorker, ({ WorkerConfiguration config ->
                config.isolationMode = IsolationMode.NONE
                config.params index
            } as Action))
        }
    }

    static class DeployArtifactWorker implements Runnable {
        int index

        @Inject
        DeployArtifactWorker(Integer index) {
            this.index = index
        }

        @Override
        void run() {
            def storage = deployerStorage.get(index)
            storage.artifact.doDeploy(storage.project, storage.ctx)
        }
    }
}
