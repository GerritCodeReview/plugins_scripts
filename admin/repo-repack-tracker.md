Repo Repack Tracker
==============================

DESCRIPTION
-----------
Check for each project configured if a gc or a repack process is running.

Configuration
=========================

The repo-repack-tracker plugin is configured in
$site_path/etc/gerrit.config` files, example:

```text
[plugins "repo-repack-tracker"]
    considerGcStaleAfter = 1h
    considerRepackStaleAfter = 12h
    project = test
```

Configuration parameters
---------------------

=======
```plugins.repo-repack-tracker.considerRepackStaleAfter```
:  If any of the files checked for determining if the repack is running has the modified date older than this value, then
the repack is considered stale (not running). If a time unit suffix is not specified, `minutes` is assumed.

Default: 1 hour.

```plugins.repo-repack-tracker.considerGcStaleAfter```
:  If any of the files checked for determining if the gc is running has the modified date older than this value, then
the GC is considered stale (not running). If a time unit suffix is not specified, `minutes` is assumed.

Default: 12 hours.

```plugins.repo-repack-tracker.project```
:  The name of the repository to check.
   May be specified more than once to specify multiple projects, for example:

   ```
   project = foo
   project = bar
   ```

Metrics
---------------------
Currently, the metrics exposed are the following:

```groovy_repo_gc_tracker_is_repack_running_per_project_<repo_name>```
:  Indicates if the repack is currently running for the <repo_name>.
The <repo_name> is sanitised to prevent the introduction of invalid characters for a metric name and to remove
the risk of collisions (between the sanitized metric names).
Repack is considered running when its value is greater than 0 .

```groovy_repo_gc_tracker_is_gc_running_per_project_<repo_name>```
:  Indicates if the gc is currently running for the <repo_name>.
The <repo_name> is sanitised to prevent the introduction of invalid characters for a metric name and to remove
the risk of collisions (between the sanitized metric names).
GC is considered as running when its value is greater than 0 .
