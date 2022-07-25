# jenkins-toolkit
Misc tools for Jenkins

Forked from https://github.com/sghill/jenkins-toolkit

# Fetch and categorize failures
Downloads failed job console logs from jenkins and categorizes them into similar exceptions
1. Run `io.moderne.jenkins.failjobs.FetchFailed` which spits failed jenkins job console logs from the last 24 hours into {project root}/jenkins-failed
1. Run `io.moderne.jenkins.failjobs.CategorizeFailuresHtml` reads {project root}/jenkins-failed, writes multiple html files to {project root}/jenkins-failed-html, open index.html, should be obvious
