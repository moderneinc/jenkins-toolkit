import hudson.model.AbstractBuild
import hudson.model.AbstractProject
import hudson.model.Result
import jenkins.model.Jenkins

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BuildDateInfo {
    int total
    int failed

    BuildDateInfo(total, failed) {
        this.total = total
        this.failed = failed
    }
}

def grouped = new HashMap<LocalDate, BuildDateInfo>()
def projects = Jenkins.get().getAllItems(AbstractProject)
for (AbstractProject<AbstractProject, AbstractBuild> project : projects) {
    for (AbstractBuild build : project.builds) {
        def buildDate = build.startTimeInMillis.with {
            LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
        }
        def buildDateInfo = grouped.get(buildDate)
        if (buildDateInfo == null) {
            buildDateInfo = new BuildDateInfo(1, build.result == Result.FAILURE ? 1 : 0)
            grouped.put(buildDate, buildDateInfo)
        } else {
            buildDateInfo.total++
            if (build.result == Result.FAILURE)
                buildDateInfo.failed++
        }
    }
}

println("date, total, failed")
for (def e : new TreeMap<LocalDate, BuildDateInfo>(grouped).entrySet()) {
    println("${e.key}, ${e.value.total}, ${e.value.failed}")
}