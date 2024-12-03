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
[plugins "repo-gc-tracker"]
    checkInterval = 30s
    considerStaleAfter = 12h
    project = test
```

Configuration parameters
---------------------

=======
```plugins.repo-gc-tracker.checkInterval```
:  Frequency of the gc running check operation. Used to avoid aggressive metrics collection. If a time unit suffix
is not specified, `seconds` is assumed.

Default: 30 seconds.

```plugins.repo-gc-tracker.considerStaleAfter```
:  If any of the files checked for determining if the gc is running has the modified date older than this value, then
the GC is considered stale (not running). If a time unit suffix is not specified, `milliseconds` is assumed.

Default: 12 hours.

```plugins.repo-gc-tracker.project```
:  The name of the repository to check.
   May be specified more than once to specify multiple projects, for example:

   ```
   project = foo
   project = bar
   ```

Metrics
---------------------
Currently, the metrics exposed are the following:

```groovy_repo_gc_tracker_is_gc_running_per_project_<repo_name>```
:  Indicates if the gc is currently running for the <repo_name>.
GC is considered as running when its value is greater than 0 .
