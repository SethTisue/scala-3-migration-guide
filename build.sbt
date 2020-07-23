import org.apache.commons.io.FileUtils
import sbt.librarymanagement.CrossVersion
import xsbti.compile.CompileAnalysis

val scala213 = "2.13.3"
val dotty = "0.25.0-RC2"

val inputDir = settingKey[File]("Directory containing the source files that will be rewitten")
val outputDir = settingKey[File]("Directory in which the source files are rewritten")
val checkDir = settingKey[File]("Directory that contains the expected rewritten files")
val migration = settingKey[String]("Target Scala version - 3.0 or 3.1")

val CompileBackward = Configuration.of("CompileBackward", "compile-bwd")

val rewrites = (project in file("rewrites"))
  .settings(
    scalaVersion := dotty,
    migration := "3.0",
    scalacOptions ++= Seq(s"-source:${migration.value}-migration", "-rewrite"),
    inputDir := baseDirectory.value / s"src/input/scala-${migration.value}",
    outputDir := target.value / s"src-managed/main/scala-${migration.value}",
    checkDir := baseDirectory.value / s"src/check/scala-${migration.value}",
    Compile / sourceGenerators += Def.task { copySources(inputDir.value, outputDir.value) },
    Test / test := {
      val _ = (Compile / compile).value
      val fileChecker = new FileChecker(outputDir.value, checkDir.value, streams.value.log)
      fileChecker.run()
    }
  )


lazy val incompat = (project in file("incompat"))
  .configs(CompileBackward)
  .aggregate(
    typeInfer1, typeInfer2, typeInfer3, typeInfer4,  typeInfer5, typeOfImplicitDef, anonymousTypeParam,
    defaultParamVariance, ambiguousConversion, reflectiveCall, explicitCallToUnapply, 
    implicitView, any2stringaddConversion, typeParamIdentifier, restrictedOperator, existentialType,
    byNameParamTypeInfer
  )

// compile incompatibilities
lazy val typeInfer1 = (project in file("incompat/type-infer-1")).settings(incompatSettings)
lazy val typeInfer2 = (project in file("incompat/type-infer-2")).settings(incompatSettings)
lazy val typeInfer3 = (project in file("incompat/type-infer-3")).settings(incompatSettings)
lazy val typeInfer4 = (project in file("incompat/type-infer-4")).settings(incompatSettings)
lazy val typeInfer5 = (project in file("incompat/type-infer-5")).settings(incompatSettings)
lazy val typeOfImplicitDef = (project in file("incompat/type-of-implicit-def")).settings(incompatSettings)
lazy val anonymousTypeParam = (project in file ("incompat/anonymous-type-param")).settings(incompatSettings)
lazy val defaultParamVariance = (project in file("incompat/default-param-variance")).settings(incompatSettings)
lazy val earlyInitializer = (project in file("incompat/early-initializer")).settings(incompatSettings)
lazy val ambiguousConversion = (project in file("incompat/ambiguous-conversion")).settings(incompatSettings)
lazy val reflectiveCall = (project in file("incompat/reflective-call")).settings(incompatSettings)
lazy val explicitCallToUnapply = (project in file("incompat/explicit-call-to-unapply")).settings(incompatSettings)
lazy val any2stringaddConversion = (project in file("incompat/any2stringadd-conversion")).settings(incompatSettings)
lazy val typeParamIdentifier = (project in file("incompat/type-param-identifier")).settings(incompatSettings)
lazy val restrictedOperator = (project in file ("incompat/restricted-operator")).settings(incompatSettings)
lazy val existentialType = (project in file ("incompat/existential-type")).settings(incompatSettings)
lazy val byNameParamTypeInfer = (project in file ("incompat/by-name-param-type-infer")).settings(incompatSettings)

// runtime incompatibilities
lazy val implicitView = (project in file("incompat/implicit-view")).settings(runtimeIncompatSettings)

lazy val incompatSettings = inConfig(CompileBackward)(Defaults.compileSettings) ++ 
  Seq(
    scalaVersion := dotty,
    crossScalaVersions := List(scala213, dotty),
    scalacOptions ++= CrossVersion.partialVersion(scalaVersion.value).toSeq.flatMap {
      case (0, _) => Seq()
      case _ => Seq("-feature", "-deprecation", "-language:implicitConversions")
    },
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / s"src/main/scala"),
    CompileBackward / unmanagedSourceDirectories := Seq(baseDirectory.value / s"src/main/scala-2.13"),
    CompileBackward / managedClasspath := (managedClasspath in Compile).value,
    Test / test := {
      val _ = (Compile / compile).value
      checkIncompatibility(
        name.value,
        scalaVersion.value,
        (CompileBackward / compile).result.value,
        streams.value.log
      )
    }
  )

lazy val runtimeIncompatSettings = inConfig(CompileBackward)(Defaults.compileSettings) ++
  Seq(
    scalaVersion := dotty,
    crossScalaVersions := List(scala213, dotty),
    scalacOptions ++= CrossVersion.partialVersion(scalaVersion.value).toSeq.flatMap {
      case (0, _) => Seq("-language:implicitConversions")
      case _ => Seq("-language:implicitConversions")
    },
    Compile / unmanagedSourceDirectories := Seq(baseDirectory.value / s"src/main/scala"),
    CompileBackward / unmanagedSourceDirectories := Seq(baseDirectory.value / s"src/main/scala-2.13"),
    CompileBackward / managedClasspath := (managedClasspath in Compile).value,
    Test / test := {
      val _ = (Compile / run).toTask("").value
      checkRuntimeIncompatibility(
        name.value,
        scalaVersion.value,
        (CompileBackward / run).toTask("").result.value,
        streams.value.log
      )
    }
  )

def copySources(inputDir: File, outputDir: File): Seq[File] = {
  if (outputDir.exists) FileUtils.deleteDirectory(outputDir)
  FileUtils.copyDirectory(inputDir, outputDir)
  outputDir.listFiles.filter(_.isFile)
}

def checkIncompatibility(name: String, scalaVersion: String, compileResult: Result[CompileAnalysis], log: Logger): Unit = {
  CrossVersion.partialVersion(scalaVersion).foreach {
    case (0, _) => compileResult match {
      case Value(_) => 
        throw new MessageOnlyException(
          "Compilation has succeeded but failure was expected. " + 
          s"The '$name' incompatibility is probably fixed, in version $scalaVersion."
        )
      case Inc(_) =>
        log.info(s"$name is incompatible with $scalaVersion")
    }
    
    case (2, _) => compileResult match { 
      case Value(_) => ()
      case Inc(_) => 
        throw new MessageOnlyException(s"$name does not compile with version $scalaVersion anymore.")
    }

    case _ => ()
  }
}

def checkRuntimeIncompatibility(name: String, scalaVersion: String, runResult: Result[Unit], log: Logger): Unit = {
  CrossVersion.partialVersion(scalaVersion).foreach {
    case (0, _) => runResult match {
      case Value(_) => 
        throw new MessageOnlyException(
          "Run has succeeded but failure was expected. " + 
          s"The '$name' incompatibility is probably fixed, in version $scalaVersion."
        )
      case Inc(_) =>
        log.info(s"$name is incompatible with $scalaVersion")
    }
    
    case (2, _) => runResult match { 
      case Value(_) => ()
      case Inc(_) => 
        throw new MessageOnlyException(s"$name does not run successfully with version $scalaVersion anymore.")
    }

    case _ => ()
  }
}
