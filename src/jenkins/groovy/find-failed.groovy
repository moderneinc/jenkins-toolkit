import hudson.model.AbstractBuild
import hudson.model.AbstractProject
import hudson.model.Result
import jenkins.model.Jenkins

import java.time.Instant
import java.time.temporal.ChronoUnit

def threshold = Instant.now().minus(1, ChronoUnit.DAYS)
def projects = Jenkins.get().getAllItems(AbstractProject)
for (AbstractProject<AbstractProject, AbstractBuild> project : projects) {
    if (!project.getName().startsWith('jenkinsci')) {
        continue
    }
    for (AbstractBuild build : project.builds) {
        if (build.result != Result.FAILURE) {
            continue
        }
        def started = build.startTimeInMillis.with { Instant.ofEpochMilli(it) }
        if (started.isBefore(threshold)) {
            continue
        }
        def jobName = project.fullName.replace('/', '-').replace(',', '-')
        def consoleTextUrl = build.absoluteUrl + 'consoleText'
        println([jobName, build.id, consoleTextUrl].join("\t"))
    }
}
null
