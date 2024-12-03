Repo GC Tracker
==============================

DESCRIPTION
-----------
Check for each project configured if a Garbage Collection process is running.

Configuration
=========================

The repo-gc-tracker plugin is configured in
$site_path/etc/gerrit.config` files, example:

```text
[plugins "@PLUGIN@"]
    checkIntervalSec = 10
    project = test
```

Configuration parameters
---------------------

=======
```plugins.@PLUGIN@.checkIntervalSec```
:  Frequency of the gc running check operation. The value is expressed as
   number of seconds.

Default: 10.

```plugins.@PLUGIN@.project```
:  The name of the repository to check.
   May be specified more than once to specify multiple projects, for example:

   ```
   project = foo
   project = bar
   ```

Metrics
---------------------
Currently, the metrics exposed are the following:

```groovy_repo_gc_tracker_is_gc_running_per_project_<project>```
:  Indicates if the gc is currently running for the <project>.
GC is considered as running when its value is greater than 0 .
