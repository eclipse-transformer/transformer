# Eclipse Transformer

The [Eclipse Transformer project](https://projects.eclipse.org/projects/technology.transformer) is part of the [Eclipse Technology top-level project](https://projects.eclipse.org/projects/technology).

Eclipse Transformer provides tools and runtime components that transform Java binaries, such as individual class files and complete JARs and WARs, mapping changes to Java packages, type names, and related resource names.

While the initial impetus for the project was the Jakarta EE package renaming issue, the scope of the project is broader to support other renaming scenarios. For example, shading.

We operate under the [Eclipse Code of Conduct](https://eclipse.org/org/documents/Community_Code_of_Conduct.php) to promote fairness, openness, and inclusion.

## Build Status

[![CI Build](https://github.com/eclipse-transformer/transformer/actions/workflows/cibuild.yml/badge.svg)](https://github.com/eclipse-transformer/transformer/actions/workflows/cibuild.yml)

## Latest release

[![Maven Central](https://img.shields.io/maven-central/v/org.eclipse.transformer/org.eclipse.transformer.parent.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22org.eclipse.transformer%22)

## Project licence

[![License](https://img.shields.io/badge/License-EPL%202.0-green.svg)](https://opensource.org/licenses/EPL-2.0)
[![License](https://img.shields.io/badge/License-APL%202.0-green.svg)](https://opensource.org/licenses/Apache-2.0)

All Eclipse Transformer project materials are made available under the Eclipse Public
License 2.0 or the Apache 2.0 license. You can choose which license you wish
to follow.  Please see our [LICENSE](LICENSE) file for more details.

## Summary

The Eclipse Transformer is a generic framework for transforming resources (files and archive) based on updates specified by property files.

The Eclipse Transformer is provided with two specific built-in capabilities:

* To update the target resources according to user specified rules data, which includes Jakarta package renaming rules.  The target resource is an JavaEE version of an implementation jar, or is a JavaEE version of an OSGi implementation jar, or is a JavaEE version of an application archive.

* To shade a target resource according to user specified rules, including, in particular, package renaming rules.  The target resource is an implementation jar.

In addition to the specific built-in capabilities, other transformations may be performed by changing the rules data, or by adding new types of update action.

The Eclipse Transformer is provided with Command Line and with Java APIs:

* Transformer classes may be used directly within java code.

* Transformation function is accessible through two Command Line APIs.  A generic command line API, which requires user specified update data, may be used.  A Jakarta command line API, which has built-in Jakarta rules data, may also be used.

## Scope

The Eclipse Transformer operates on resources, including but not limited to the following:

Simple resources:

* Java class files
* OSGi feature manifest files
* Properties files
* Service loader configuration files
* Text files (of several types: java source, XML, TLD, HTML, and JSP)

Container resources:

* Directories
* Java archives (JAR, WAR, RAR, and EAR files)
* ZIP archives

## APIs

The Eclipse Transformer provides three core APIs: A java entry point and two command line entry points:

* Class **org.eclipse.transformer.Transformer** provides for direct invocation from java.

* Class **org.eclipse.transformer.cli.TransformerCLI** provides a standard **main** command line entry point.

* Class **org.eclipse.transformer.cli.JakartaTransformerCLI** provides an addition **main** command line entry point.  **JakartaTransformerCLI** uses **JakartaTransform** which is packaged with default update properties files and supplies these as default property files for the several update properties files.

## Use Cases

Primary use cases are as follows:

* **Simple resource case**: A target simple file is specified to be transformed.  The command line interface is used to create a transformed copy of the file.  Particular transformations are selected for the file based on the type of the file.  Particular updates made by the transformation are based on property files which are specified to the transformer.

* **Archive case**: A target archive is specified to be transformed.  The command line interface is used to create a transformed copy of the archive.  All contents of the target archive, including nested archives, are transformed.  Particular transformations are selected for the archive based on the type of the file.  Particular updates made by the transformation are based on property files which are specified to the transformer.

* **Directory case**: A target directory is specified to be transformed.  The command line interface is used to create a transformed copy of all the resources within the directory, and within all subdirectories of the specified directory.  Transformation of each file within the target directory is according the simple resource case and the archive case.

Possible secondary use cases are as follows:

* **Classloading case**: A java class action is created and initialized, and is wired into a custom class loader.  When loading resources, in particular, when loading a class resource, the java class action is used to transform the contents of the resource before making them available to the class loader.

## Usage

The transformer CLI can be invoked by using `java -jar org.eclipse.transformer.cli-<version>.jar` from now on referred as `TransformerCLI`. This file can be found in the `org.eclipse.transformer.cli/target` folder when you build this project by yourself, or you can download and extract the `org.eclipse.transformer.cli-<version>-distribution.jar` into a directory of your choice and start it from there.

The primary use of the Eclipse Transformer is to create an updated copy an input file or directory.  Generally:

    TransformerCLI input [ output ] [ options ... ]

To get more information on available options use:

    TransformerCLI --help

For simplicity, the transformer does not perform any "in-place" updates.  Replacement of an input file with an output file must be performed as an operation external to the transformer.

The input file is expected to be either a specific type of file (for example, a java class file), or a container type file; usually, a zip, java, or JavaEE  archive, or a directory.

In addition to the output file, the transformer produces a change report.  The change report tells if any updates were made to the target file, and provides details on what changes were made.  What details are provided depends on the type of file which is transformer.

When the input file is a container type file, the transformer is applied to all the elements of the container.

The transformer recursively processes nested archives.  For example, Web Application Archives (WAR files) located within Enterprise Application Archives (EAR files) are processed.

## Updates

A core function of the Eclipse Transformer is to locate java package references within resources and to update these references using package rename data.

Additional functions are to update OSGi bundle metadata, for example, to change the bundle metadata, or to change package versions for renamed package references.

For class shading, usual target resources are java class files or a java archives.

For Jakarta related updates, usual target resources are implementation jar files and JavaEE application archives.

## Rules Data Files

Update data is specified using property files.  A set of default property files are included in the packaging of the Jakarta command line interface.  User specified property files must be provided when using the generic command line interface.  Property files are specified using command line options:

| Command Line Option | Description                 | Jakarta Example              |
|---------------------|-----------------------------|------------------------------|
| -ts --selection     | Target selections           | jakarta-selection.properties |
| -tr --renames       | Package renames             | jakarta-renames.properties   |
| -tv --versions      | Package version assignments | jakarta-versions.properties  |
| -tb --bundles       | Bundle identity data        | jakarta-bundles.properties   |
| -td --direct        | Java class direct updates   | jakarta-direct.properties    |

All property options may be specified zero, one, or multiple times.

When the same property option is specified multiple times, the property file data for that option is read first to last, where later data overwrites earlier data. Overwriting occurs on individual property values.

An additional core property file is used as a master table for text updates:

| Command Line Option | Description      | Jakarta Example                |
|---------------------|------------------|--------------------------------|
| -tf --text          | Text master data | jakarta-text-master.properties |

The master text properties file maps file selection patterns (for example, "\*.xml", or "\*.html") to specific text properties files.

A special "immediate" option exists that allows the specification of rules data directly on the command line:

| Command Line Option | Description    | Jakarta Example |
|---------------------|----------------|-----------------|
| -ti --immediate     | Immediate data | None            |

The immediate option has the format:

    -ti ( tr | tv | tb | td | tf ) key value

The first parameter is the tag for one of the usual property options, specified without the '-' prefix.

The "key" and "value" values are composed to create a property definition, the equivalent of specifying "key=value" in a properties file.  The property value is taken as new property data according to the tag which was used.

Immediate options are processed after property file options are processed.  Individual property definitions obtained as immediate data are overlaid onto and replace individual property definitions obtained from property files.

Immediate options may be used with or without specifying a corresponding properties file.

## Artifacts

The Eclipse Transformer artifacts are released to Maven Central.

Snapshot artifacts are released to the Central Portal snapshot repository: [https://central.sonatype.com/repository/maven-snapshots/](https://central.sonatype.com/service/rest/repository/browse/maven-snapshots/org/eclipse/transformer/).

To use the snapshot artifacts in your Maven build, you can configure the Central Portal snapshot repository.

```xml
<repositories>
    <repository>
        <name>Central Portal Snapshots</name>
        <id>central-portal-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

To use the snapshot transformer maven plugin in your Maven build, you can configure the Central Portal snapshot repository.

```xml
<pluginRepositories>
    <pluginRepository>
        <name>Central Portal Snapshots</name>
        <id>central-portal-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </pluginRepository>
</pluginRepositories>
```

## Contributing

If you would like to contribute to Transformer, check out the [contributing guide](CONTRIBUTING.md) for more information.

## Additional documentation

See the following files for more detailed information:

* [INFRA.md](INFRA.md)      Project infrastructure and build notes
* [DEV.md](DEV.md)        Developer overview
* [RULES.md](RULES.md)      Transformer actions and rules details
