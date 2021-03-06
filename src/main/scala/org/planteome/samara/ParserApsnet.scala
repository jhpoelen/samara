package org.planteome.samara

import java.io.File

import net.ruippeixotog.scalascraper.model.Document
import net.ruippeixotog.scalascraper.scraper.ContentExtractors._

import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._

import scala.io.Source


// http://www.apsnet.org/publications/commonnames/Pages/default.aspx

case class Disease(name: String,
                   verbatimPathogen: String = "", pathogen: String, pathogenId: String = "",
                   verbatimHost: String = "", host: String, hostId: String = "",
                   hostPartName: String = "", hostPartId: String = "",
                   citation: String = "")

abstract class ParserApsnet extends TermFinder with Scrubber {

  def parsePageIndex(doc: Document): Iterable[String] = {
    doc >> elements(".link-item") >> attrs("href")("a")
  }

  def parse(doc: Document): Iterable[Disease] = {
    val diseaseLists = doc >> elements("dt,dd")
    val elems = diseaseLists.map(elem => {
      (elem.tagName, elem >> text(elem.tagName))
    })

    val withDisease = elems
      .zipWithIndex
      .foldLeft(List.empty[String])((acc, item) => {
        item._1 match {
          case ("dt", v) => v :: acc
          case ("dd", _) => acc.head :: acc
          case _ => acc
        }
      }).reverse

    val targetTaxonNames = doc >> text("h1").map(_.replace("DisplayTitle", "").trim)

    val authorInfo = doc >> text("h4")

    val citation = s"$authorInfo. $targetTaxonNames. The American Phytopathological Society."

    val diseases = List(targetTaxonNames)
      .flatMap(targetTaxon => {
        elems.zip(withDisease)
          .filter {
            case (("dd", _), _) => true
            case _ => false
          }
          .flatMap {
            case ((_, pathogenName), diseaseName) => {
              val diseases = Seq(Disease(name = scrub(diseaseName), pathogen = pathogenName, host = targetTaxon))

              diseases.flatMap {
                disease => {
                  val hostNames: Seq[String] = extractHostNames(targetTaxon)
                  hostNames.map { hostname => disease.copy(host = hostname, verbatimHost = targetTaxon) }
                }
              }.flatMap {
                disease => {
                  val pathogenNames: Seq[String] = extractPathogenNames(pathogenName)
                  pathogenNames.map { pathogen => disease.copy(pathogen = pathogen, verbatimPathogen = pathogenName) }
                }
              }
            }
          }
      })

    val expandedDiseases = diseases zip expandPrefixes(diseases.map(_.pathogen)) map {
      case ((disease, pathogenExpanded)) => disease.copy(pathogen = pathogenExpanded, citation = citation)
    }

    val resolvedDiseases = expandedDiseases
      .flatMap {
        disease => {
          matchIfNCBI(disease.host, findTerms(disease.host))
            .map(term => disease.copy(hostId = term.id))
        }
      }.flatMap {
      disease => {
        matchIfNCBI(disease.pathogen, findTerms(disease.pathogen))
          .map(pathogen => disease.copy(pathogenId = pathogen.id))
      }
    }.flatMap {
      disease => {
        noMatchIfEmpty(disease.name, findTerms(disease.name))
          .map(hostPart => disease.copy(hostPartName = hostPart.name, hostPartId = hostPart.id))
      }
    }

    resolvedDiseases.map {
      disease => {
        disease.copy(pathogen = canonize(disease.pathogen), host = canonize(disease.host))
      }
    }
  }

  private def noMatchIfEmpty(text: String, terms: List[Term]): List[Term] = {
    if (terms.nonEmpty) terms else List(Term("whole plant", "PO:0000003"))
  }

  private def matchIfNCBI(text: String, terms: List[Term]): List[Term] = {
    val ncbiTerms = terms.filter(_.id.startsWith("NCBI"))
    if (ncbiTerms.nonEmpty) terms else List(Term(text, "no:match"))
  }

  def canonize(name: String): String = {
    val capital = """(^[A-Z][a-z]{2}.*)""".r
    name match {
      case capital(capitalized) => name
      case _ => ""
    }
  }

  def expandPrefixes(names: List[String]): List[String] = {
    names.foldLeft((names.headOption.getOrElse(""), List[String]())) { (acc, name) =>
      val abbreviated = """(^\w[\.]*)\s+.*""".r
      name match {
        case abbreviated(abbr) => {
          val firstPart = if (abbr.length > 0 && acc._1.toLowerCase.startsWith(abbr.substring(0, 1).toLowerCase)) {
            name.replaceFirst(abbr, acc._1)
          } else {
            name
          }
          (acc._1, firstPart :: acc._2)
        }
        case str => (str.split("\\s").head, name :: acc._2)
      }
    }._2.reverse
  }

  lazy val nameMap: Map[String, String] = Source
    .fromInputStream(getClass.getResourceAsStream("apsnet/nameMap.tsv"))
    .getLines()
    .foldLeft(Map[String, String]()) {
      (agg, line) =>
        val split = line.split('\t')
        if (split.length < 2) {
          agg
        } else {
          agg ++ Map(split(0) -> split(1))
        }
    }

  def extractHostNames(targetTaxon: String): Seq[String] = {
    val scrubbedHosts = mapNames(Seq(targetTaxon)).map(scrub)
    mapNames(scrubbedHosts.flatMap(singleHostname))
  }

  def extractPathogenNames(sourceTaxon: String): Seq[String] = {
    val scrubbedNames = mapNames(Seq(sourceTaxon)).map(scrub)
    mapNames(scrubbedNames.flatMap(pathogenNames))
  }

  def mapNames(names: Seq[String]): Seq[String] = {
    names.flatMap { someName =>
      nameMap.get(someName) match {
        case Some("no:name") => None
        case Some(name) => name.split('|')
        case None => Seq(someName)
      }
    }
  }

  def pathogenNames(pathogenName2: String): Seq[String] = {
    val synonymSplit = """^\(=(.*)\)$""".r
    val synonymSplit3 = """^\(=(.*)$""".r
    val synonymSplit2 = """^=(.*)""".r
    val synonymSplit4 = """\(syn\.(.*)\)$""".r
    val synonymSplit5 = """\(syns\.(.*)\)$""".r
    val quoted = """‘(.*)’""".r
    val anamorph = """^\((.*morph):(.*)?\)$""".r
    val pathogenName = pathogenName2 match {
      case synonymSplit(name) => name.trim
      case synonymSplit2(name) => name.trim
      case synonymSplit3(name) => name.trim
      case synonymSplit5(name) => name.trim
      case synonymSplit4(name) => name.trim
      case quoted(name) => name.trim
      case anamorph(_, name) => name.trim
      case _ => pathogenName2
    }

    val genusSpeciesSplit = """[Gg]enus\s([^;:,\s])*(.*)""".r
    val removeParenthesis: String = pathogenName
      .replaceAll("""\s+""", " ")
      .replaceAll("""\([^\)]*\)""", ",")
    val pathogenNameProcessed = removeParenthesis match {
      case genusSpeciesSplit(genusName, postGenusName) => {
        postGenusName
      }
      case _ => pathogenName
    }

    pathogenNameProcessed.split("""[,:;]""")
      .map(_.trim)
      .filter(_.nonEmpty)
  }

  def singleHostname(scrubbedHost: String): Seq[String] = {
    val singleName = """[^:]*\((.*)\).*""".r
    Seq(scrubbedHost match {
      case singleName(name) => name
      case _ => scrubbedHost
    })
  }
}
