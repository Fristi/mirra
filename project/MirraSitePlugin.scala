import laika.ast.Path.Root
import laika.config.{MessageFilter, MessageFilters, SyntaxHighlighting}
import laika.format.Markdown
import laika.helium.Helium
import laika.helium.config.*
import laika.sbt.LaikaConfig
import laika.sbt.LaikaPlugin.autoImport.{laikaConfig, laikaExtensions, laikaIncludeAPI}
import org.typelevel.sbt.TypelevelSitePlugin
import org.typelevel.sbt.TypelevelSitePlugin.autoImport.*
import sbt.*
import sbt.Keys.*

object MirraSitePlugin extends AutoPlugin {

  override def requires = TypelevelSitePlugin
  override def trigger  = noTrigger

  override def projectSettings: Seq[Setting[_]] = Seq(
    tlSitePublishBranch := Some("master"),
    laikaIncludeAPI := true,
    laikaExtensions := Seq(Markdown.GitHubFlavor, SyntaxHighlighting),
    tlSiteHelium := {
      Helium.defaults.site
        .metadata(
          title = Some("Mirra"),
          language = Some("en"),
        )
        .site
        .topNavigationBar(
          homeLink = IconLink.internal(Root / "index.md", HeliumIcon.home),
          navLinks = Seq(
            IconLink.external("https://github.com/Fristi/mirra", HeliumIcon.github),
          ),
        )
        .site
        .landingPage(
          title       = Some("Mirra"),
          subtitle    = Some("Mirror-test your tagless final algebras in Scala"),
          license     = Some("MIT"),
          documentationLinks = Seq(
            TextLink.internal(Root / "introduction.md", "Introduction"),
          ),
          projectLinks = Seq(
            IconLink.external("https://github.com/Fristi/mirra", HeliumIcon.github),
          ),
          teasers = Seq(
            Teaser("Test Oracle Pattern",
              "Verify that your real implementation agrees with a simple in-memory model — using property-based testing."),
            Teaser("Tagless Final",
              "Works with any algebra defined in tagless final style via cats-tagless FunctorK and SemigroupalK."),
            Teaser("Multi-Framework",
              "Out-of-the-box integrations for munit + cats-effect and ZIO Test."),
            Teaser("Doobie & Skunk",
              "Database backends included; every property iteration runs inside a rolled-back transaction."),
          ),
        )
    },
  )
}
