Track Active Users
==============================

DESCRIPTION
-----------
Track user's activity over REST, SSH and UI and allow disabling inactive
accounts after the configured inactivity period.

Configuration
=========================

The track-active-users plugin is configured in
$site_path/etc/gerrit.config` files, example:

```text
[cache "@PLUGIN@.users_cache"]
  maxAge = 90d
```

Configuration parameters
---------------------

=======
```plugin.@PLUGIN@.ignoreAccountId```
:  Specify an account Id that should not be auto disabled.
   May be specified more than once to specify multiple account Ids, for example:

   ```
   ignoreAccountId = 1000001
   ignoreAccountId = 1000002
   ```

```plugin.@PLUGIN@.ignoreGroup```
:  Specify one group that includes directly or indirectly all the accounts that
   should not be auto disabled.
   May be specified more than once to specify multiple groups, for example:

   ```
   ignoreGroup = Active Developers
   ignoreGroup = Administrators
   ```

   > **NOTE**: The `Service Users` group is always added to the list of groups of
   > accounts to not disable.

```plugin.@PLUGIN@.preloadAccounts```
:  Allow to preload the active users persistent cache with all the accounts that
   are currently flagged as active on All-Users when the plugin starts.

   > **NOTE**: When this option is enabled, all users configured in Gerrit will
   > always be flagged as active when Gerrit or the plugin is started, even though
   > they were not active for a long time.

   Default: true.

```plugin.@PLUGIN@.disableAccounts```
:  Allow to disable the users that have been evicted from the persistent cache because
   of inactivity.

   > **NOTE**: When this option is enabled, as soon as a user is evicted from the
   > persistent cache is then flagged as inactive on All-Users and therefore will be
   > unable to login or execute any operation until is manually reactivated.

   Default: true.

```cache."@PLUGIN@.users_cache".maxAge```
:  Maximum allowed inactivity time for user.
   Value should use common time unit suffixes to express their setting:

   * h, hr, hour, hours
   * d, day, days
   * w, week, weeks (`1 week` is treated as `7 days`)
   * mon, month, months (`1 month` is treated as `30 days`)
   * y, year, years (`1 year` is treated as `365 days`)

   If a time unit suffix is not specified, `hours` is assumed.
   Default: 90 days

Metrics
---------------------
Currently, the metrics exposed are the following:

```groovy_track_and_disable_inactive_users_active_users```
:  Indicates the number of active users.
   A user is considered active when its inactivity period is not greater than `cache."@PLUGIN@.users_cache".maxAge` .

SSH commands
---------------------
This script exposes the following SSH commands to the Gerrit administrator:

## Name

`list`, list active accounts

## Synopsis

````
ssh -p <port> <host> track-and-disable-inactive-users list [--verbose]
```

## Description

display the list of all account-ids that are considered active and
their last activity timestamp.

The `--verbose` option produces a more detailed progress of the extraction
of the active accounts and is intended to be used for debugging purposes.

## Access

Any user who has been granted the ‘Administrate Server’ capability.

## Scripting

This command is intended to be used in scripts and not interactively, as it
may return a very long list of accounts and doesn't do any pagination.

## Examples

List all the currently active accounts.

```
$ ssh -p 29418 review.example.com track-and-disable-inactive-users list

SEE ALSO
[source] (/admin/track-and-disable-inactive-users-1.3.groovy)