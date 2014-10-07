[![Build Status](https://travis-ci.org/didclab/stork.svg)]
  (https://travis-ci.org/didclab/stork)

Introduction
============

Stork is a data transfer scheduler that manages and optimizes the movement of
data across arbitrary endpoints in a protocol-agnostic manner. Stork also
provides APIs for browsing and managing data on remote endpoints, as well as
both command line and browser clients for interacting with the system.

Clients submit jobs to a Stork server and the Stork server performs the
transfer when resources permit. The Stork server responds to any failures that
may occur during transfer automatically, handling them in an appropriate way
when possible and informing the user if a job cannot be completed.

Building
========

Building Stork requires a Java SE 7 compatible runtime (JRE) and development
kit (JDK) to be installed.

Building is as simple as running `make`.

Additional build targets can be viewed by running `make help`.

Configuring
===========

The Stork configuration file (stork.conf) can be used to change settings for
the server and client tools. The search order for the configuration file is as
follows:

1. $STORK\_CONFIG
2. ~/.stork.conf
3. /etc/stork.conf
4. $STORK/stork.conf
5. /usr/local/stork/stork.conf
6. stork.conf in currect directory

Even if the file cannot be found automatically, every valid config variable has
a default value. The Stork server will issue a warning on startup if a config
file cannot be found.

Project Structure
=================

* `bin/` — Contains scripts to execute JARs. This directory gets included in
  the release tarfile for a binary release.
* `build/` — Gets created when the project is built. Contains all class files
  generated by the Java compiler. Everything in here then gets put into
  stork.jar after building.
* `doc/` — Contains documentation after running `make doc`.
* `lib/` — Contains external libraries that get included in stork.jar on build.  
* `libexec/` — Stork searches here for transfer module binaries when it is run.
  Gets included in the binary release tarfile.
* `stork/` — Includes all the Java source files for Stork.
* `web/` — Includes static web files for the browser client.
