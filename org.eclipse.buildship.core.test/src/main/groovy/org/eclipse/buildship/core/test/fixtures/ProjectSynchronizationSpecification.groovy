package org.eclipse.buildship.core.test.fixtures

import com.google.common.util.concurrent.FutureCallback

import com.gradleware.tooling.toolingclient.GradleDistribution
import com.gradleware.tooling.toolingmodel.OmniBuildEnvironment
import com.gradleware.tooling.toolingmodel.OmniGradleBuildStructure
import com.gradleware.tooling.toolingmodel.repository.FixedRequestAttributes
import com.gradleware.tooling.toolingmodel.util.Pair

import org.eclipse.core.resources.IProject
import org.eclipse.core.runtime.jobs.Job

import org.eclipse.buildship.core.CorePlugin
import org.eclipse.buildship.core.projectimport.ProjectImportConfiguration
import org.eclipse.buildship.core.projectimport.ProjectPreviewJob
import org.eclipse.buildship.core.util.gradle.GradleDistributionWrapper
import org.eclipse.buildship.core.util.progress.AsyncHandler
import org.eclipse.buildship.core.workspace.NewProjectHandler


abstract class ProjectSynchronizationSpecification extends WorkspaceSpecification {

    protected static final GradleDistribution DEFAULT_DISTRIBUTION = GradleDistribution.fromBuild()

    protected void synchronizeAndWait(File location, NewProjectHandler newProjectHandler = NewProjectHandler.IMPORT_AND_MERGE) {
        startSynchronization(location, DEFAULT_DISTRIBUTION, newProjectHandler)
        waitForGradleJobsToFinish()
    }

    protected void importAndWait(File location, GradleDistribution distribution = DEFAULT_DISTRIBUTION) {
        startSynchronization(location, distribution, NewProjectHandler.IMPORT_AND_MERGE)
        waitForGradleJobsToFinish()
    }

    protected void startSynchronization(File location, GradleDistribution distribution = DEFAULT_DISTRIBUTION, NewProjectHandler newProjectHandler = NewProjectHandler.IMPORT_AND_MERGE) {
        FixedRequestAttributes attributes = new FixedRequestAttributes(location, null, distribution, null, [], [])
        CorePlugin.gradleWorkspaceManager().getGradleBuild(attributes).synchronize(newProjectHandler)
    }

    protected void synchronizeAndWait(IProject... projects) {
        CorePlugin.gradleWorkspaceManager().getGradleBuilds(projects as Set).synchronize(NewProjectHandler.IMPORT_AND_MERGE)
        waitForGradleJobsToFinish()
    }

    protected void previewAndWait(File location, FutureCallback<Pair<OmniBuildEnvironment, OmniGradleBuildStructure>> resultHandler) {
        def job = newProjectPreviewJob(location, DEFAULT_DISTRIBUTION, resultHandler)
        job.schedule()
        job.join()
    }

    private ProjectPreviewJob newProjectPreviewJob(File location, GradleDistribution distribution, FutureCallback<Pair<OmniBuildEnvironment, OmniGradleBuildStructure>> resultHandler) {
        ProjectImportConfiguration configuration = new ProjectImportConfiguration()
        configuration.gradleDistribution = GradleDistributionWrapper.from(distribution)
        configuration.projectDir = location
        configuration.applyWorkingSets = true
        configuration.workingSets = []
        new ProjectPreviewJob(configuration, [], AsyncHandler.NO_OP, resultHandler)
    }

    protected def waitForGradleJobsToFinish() {
        Job.jobManager.join(CorePlugin.GRADLE_JOB_FAMILY, null)
    }
}
