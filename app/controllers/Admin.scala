package controllers

import com.google.gdata.client.spreadsheet._
import com.google.gdata.data.spreadsheet._
import com.google.gdata.util._

import models._
import models.Rating._

import play.api.Logger
import play.api.data._
import play.api.data.Forms._
import play.api.mvc._

import scala.collection.JavaConversions._

import views._

object Admin extends Controller with Secured {
	val editUserForm = Form(
		tuple(
			"id" -> number,
			"email" -> text,
			"name" -> text,
			"password" -> optional(text), 
			"admin_email" -> text,
			"admin_password" -> optional(text)
		) verifying ("Ungültige Administrator E-Mail oder falsches Passwort", result => result match {
				case (_, _, _, password, admin_email, admin_password) => {
					!password.isDefined || 
					User.authenticate(admin_email, admin_password.getOrElse("")).map(_.role == Role.Admin).getOrElse(false)
				}
		}))

	def prefs = IsAdmin { user => implicit request => 
		Ok(html.adminPrefs( User.findAll(), editUserForm, user ))
	}

	def editUser = IsAdmin { user => implicit request => 
		editUserForm.bindFromRequest.fold(
			formWithErrors => BadRequest(html.adminPrefs( User.findAll(), formWithErrors, user)),
			{ case (id, email, name, password, _, _) => {
				User.edit(id, email, name, password, None) 
				Redirect(routes.Admin.prefs.url + "#users").flashing("user_success" -> {"Änderungen an Nutzer "+name+" erfolgreich gespeichert"})
			} }
		) // bindFromRequest
	}

	val importForm = Form(
		tuple(
			"author" -> text.verifying("Unbekannter Autor", author => Author.load(author).isDefined),
			"spreadsheet" -> text))

	def viewImportForm  = IsAdmin { user => implicit request =>
		Ok(html.importview(importForm, user))
	}

	def importStatements = IsAdmin { user => implicit request =>
		importForm.bindFromRequest.fold(
			formWithErrors => BadRequest(html.importview(formWithErrors, user)),
			{ case (author_name, spreadsheet) => loadSpreadSheet(author_name, spreadsheet) }
		) // bindFromRequest
	}
	
	def loadSpreadSheet(author_name: String, spreadsheet: String) : Result = {		
		class ImportException(message: String) extends java.lang.Exception(message)
		case class ImportRow(title: String, category: String, quote: Option[String], quote_source: Option[String], tags: Option[String], merged_id: Option[Long])
		
		try {
			val author = Author.load(author_name).get
			val service = new SpreadsheetService("import");
				
			// Define the URL to request.  This should never change.
			val WORKSHEET_FEED_URL = new java.net.URL(
				"http://spreadsheets.google.com/feeds/worksheets/"+spreadsheet+"/public/values");

			val worksheet = service.getFeed(WORKSHEET_FEED_URL, classOf[WorksheetFeed]).getEntries().get(0);
			val listFeed = service.getFeed(worksheet.getListFeedUrl(), classOf[ListFeed]);

			var cRows = collection.mutable.ArrayBuffer.empty[ImportRow]
			for (feedrow <- listFeed.getEntries()) {
				val custom = feedrow.getCustomElements()

				val strCategory = custom.getValue("ressort").trim
				if (strCategory == null) throw new ImportException("Fehlendes Ressort bei Wahlversprechen Nr. "+(cRows.length+1))

				val strTitle = custom.getValue("titel").trim
				if (strTitle == null) throw new ImportException("Fehlender Titel bei Wahlversprechen Nr. "+(cRows.length+1))

				val strQuote = if (custom.getValue("zitat") == null) None else Some(custom.getValue("zitat").trim)
				val strSource = if (custom.getValue("quelle") == null) None else Some(custom.getValue("quelle").trim)
				val strTags = if (custom.getValue("tags") == null) None else Some(custom.getValue("tags").trim)
				val merged_id = if (author.rated || custom.getValue("merged") == null) None else {
					try {
						Some( java.lang.Long.parseLong( custom.getValue("merged"), 10 ) )
					} catch {
						case e : NumberFormatException => None
					}
				}
				Logger.info("Found statement " + strTitle)
				cRows += new ImportRow(strTitle, strCategory, strQuote, strSource, strTags, merged_id)
			}

			Logger.info("Found " + cRows.length + " statements. Begin import.")

			import play.api.Play.current
			play.api.db.DB.withTransaction { c =>

				var mapcategory = collection.mutable.Map.empty[String, Category]
				mapcategory ++= (for(c <- Category.loadAll(c)) yield (c.name, c))
				var nCategoryOrder = if(mapcategory.isEmpty) 0 else mapcategory.values.maxBy(_.order).order

				var maptag = collection.mutable.Map.empty[String, Tag]
				maptag ++= (for( t <- Tag.loadAll(c) ) yield (t.name, t))

				cRows.foreach(importrow => {
					Logger.info("Create statement " + importrow.title + " with category " + importrow.category)					

					val category = mapcategory.getOrElseUpdate(
						importrow.category,
						{
							nCategoryOrder = nCategoryOrder + 1
							Logger.info("Create category " + importrow.category + " with order " + nCategoryOrder)
							Category.create(c, importrow.category, nCategoryOrder)
						}
					)

					val stmt = Statement.create(c, importrow.title, author, category, importrow.quote, importrow.quote_source, if(author.rated) Some(Rating.Unrated) else None, importrow.merged_id)
					importrow.tags.foreach( 
							_.split(',').map(_.trim).distinct.foreach( tagname => {
								val tag = maptag.getOrElseUpdate(tagname, { Tag.create(c, tagname) })
								Tag.add(c, stmt, tag)
							})
					)
				})
			}

			Redirect(routes.Application.index).flashing("success" -> (cRows.length+" Wahlversprechen erfolgreich importiert."))
		} catch {
			case e: ImportException => {
				Redirect(routes.Admin.viewImportForm).flashing("error" -> e.getMessage())
			}
			case e: Exception => {
				Logger.error(e.toString)
				Logger.error(e.getStackTraceString)
				Redirect(routes.Admin.viewImportForm).flashing("error" -> "Beim Importieren ist ein Fehler aufgetreten.")
			}
		}		
	}
}