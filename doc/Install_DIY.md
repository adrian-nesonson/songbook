Songbook Do It Yourself
=======================

This tutorial presents how to get a songbook server on your own server.

Java 8
------

Songbook is meant to be easy to install and it is, except for one thing: Java 8.
We really wanted to test out the new language features. 
They are ground breaking.
There are lots of tutorial online to install java 8 depending on your platform.
For Windows or MacOS X, just go to Oracle Java download [page](http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html) and install it.
For linux, it depends on your distribution. 
Here are some tutorials for some distributions:

* Ubuntu [here](http://ubuntuhandbook.org/index.php/2013/07/install-oracle-java-6-7-8-on-ubuntu-13-10/) or [here](http://www.devsniper.com/install-jdk-8-on-ubuntu/).
* Debian [here](http://linuxg.net/how-to-install-the-oracle-java-8-on-debian-wheezy-and-debian-jessie-via-repository/) or [here](http://tutorialforlinux.com/2014/03/26/how-to-install-oracle-jdk-8-on-debian-squeeze-6-32-64bit-easy-guide/).
* Fedora, CentOS and RHEL [here](http://tecadmin.net/install-java-8-on-centos-rhel-and-fedora/) or [here](http://tutorialforlinux.com/2014/03/16/how-to-install-oracle-jdk-8-on-fedora-16-17-18-19-20-21-3264bit-linux-easy-guide/).

 
Linux
-----

Download and unzip Songbook.

```Shell
wget https://drone.io/github.com/kawane/songbook/files/build/distributions/songbook-0.4.zip
unzip songbook-0.4.zip
rm -f songbook-0.4.zip
cd songbook-0.4
```

Run Songbook

```Shell
./bin/songbook
```

MacOS X
--------
Download and unzip Songbook.

```Shell
wget https://drone.io/github.com/kawane/songbook/files/build/distributions/songbook-0.4.zip
unzip songbook-0.4.zip
rm -f songbook-0.4.zip
cd songbook-0.4
```

Run Songbook

```Shell
./bin/songbook
```


Windows
-------

Download and unzip Songbook from `https://drone.io/github.com/kawane/songbook/files/build/distributions/songbook-0.4.zip`.

Run Songbook
```Shell
./bin/songbook.bat
```



Songbook
--------

Now your server is up and running, check the [Getting Started](Getting_Started.md) to add your songs.
