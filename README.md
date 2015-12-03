Gerrit scripting plugins collection
===================================

Overview
--------
This repository contains a collection of Gerrit scripting plugins
that are intended to provide simple and useful extensions.

How to run the scripting plugins
--------------------------------
Gerrit needs to be able to recognise the scripts syntax and being able to load them as plugins.

In order to be able to run Groovy scripts, you need to install first the 
[Groovy scripting provider](https://gerrit.googlesource.com/plugins/scripting/groovy-provider/)
and then copy the Groovy scripts under your Gerrit /plugins directory.

Similarly for Scala scripts, you need to install the 
[Scala scripting provider](https://gerrit.googlesource.com/plugins/scripting/scala-provider/)
and then copy the Scala scripts under your Gerrit /plugins directory.

[Administration Scripts](/admin/)
------------------------
