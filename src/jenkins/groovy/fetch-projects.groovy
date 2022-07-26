import hudson.model.AbstractBuild
import hudson.model.AbstractProject
import hudson.model.Result
import hudson.tasks.Builder

import jenkins.model.Jenkins

import java.time.Instant
import java.time.temporal.ChronoUnit

def threshold = Instant.now().minus(24, ChronoUnit.HOURS)
def projects = Jenkins.get().getAllItems(AbstractProject)
for (AbstractProject<AbstractProject, AbstractBuild> project : projects) {

    def buildTool = ""
    for (Builder builder : project.builders) {
        if ("hudson.plugins.gradle.Gradle".equals(builder.getClass().getCanonicalName())) {
            buildTool = "gradle"
            break
        } else if ("org.jfrog.hudson.maven3.Maven3Builder") {
            buildTool = "maven"
            break
        }
    }
    for (AbstractBuild build : project.builds) {
        def status = (build.result != Result.FAILURE) ? "success" : "failure"
        def started = build.startTimeInMillis.with { Instant.ofEpochMilli(it) }
        if (started.isBefore(threshold)) {
            continue
        }
        def jobName = project.fullName.replace(',', '&&&')
        def consoleTextUrl = build.absoluteUrl + 'consoleText'
        println([jobName, build.id, buildTool, status, consoleTextUrl].join("\t"))
    }
}
