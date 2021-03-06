package controllers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import models.Book;
import models.User;
import models.UserBookConnection;
import models.UserBookConnection.ConnectionType;
import play.Logger;
import play.libs.F;
import play.libs.F.Promise;
import play.libs.WS;
import play.libs.WS.HttpResponse;
import play.mvc.With;
import resources.BookResource;
import resources.BooksListResource;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import controllers.SecurityController.Secured;
import controllers.hypercore.BasicController;

@With(SecurityController.class)
public class BooksController extends BasicController {

	private static final String GOOGLE_BOOK = "https://www.googleapis.com/books/v1/volumes/";

	public static void books(Integer page, Integer size) {
	    BooksListResource booksListRes = new BooksListResource();
	    booksListRes.withItems(getBooksList(page, size));
	    booksListRes.withPage(page);
	    booksListRes.withSize(size);
	    booksListRes.withTotalElements(getTotalBooksCount());
	    booksListRes.build();
	    
		render(booksListRes);
	}
	
	public static void bestBooks(Integer page, Integer size){
	    BooksListResource booksListRes = new BooksListResource();
	    booksListRes.withItems(getBestBooksList(page, size));
	    booksListRes.withPage(page);
	    booksListRes.withSize(size);
	    booksListRes.withTotalElements(getTotalBooksCount());
	    booksListRes.build();
	    
		render(booksListRes);
	}

	public static void book(String externalId) {
		render(new BookResource(getBookByExternalId(externalId)));
	}

    public static void searchBooks(String query) {
        BooksListResource booksListRes = new BooksListResource();
        booksListRes.withItems(getBooks(query));
        booksListRes.withPage(0);
        booksListRes.withSize(9);
        booksListRes.withTotalElements(9l);
        booksListRes.build();

        render(booksListRes);
    }

	@Secured
	public static void mark(String externalId, UserBookConnection.ConnectionType type) {
		User user = SecurityController.getAuthenticatedUser();
		Book book = getBookByExternalId(externalId);

		if (!book.isPersistent()) {
			book.save();
		}

		removeExistingUserBookConnectionIfExists(type, user, book);
		saveNewUserBookConnection(type, user, book);

		ok();
	}

	/*
	 * Utility methods
	 */

	private static void removeExistingUserBookConnectionIfExists(ConnectionType type, User user, Book book) {
		UserBookConnection connection = UserBookConnection.find("byUserAndBook", user, book).first();
		if (connection != null) {
			connection.delete();
		}
	}

	private static void saveNewUserBookConnection(UserBookConnection.ConnectionType type, User user, Book book) {
		UserBookConnection connection = new UserBookConnection();
		connection.book = book;
		connection.user = user;
		connection.type = type;
		connection.save();
	}

	private static Long getTotalBooksCount() {
		return Book.count();
	}

	private static Book getBookByExternalId(String externalId) {
		Book book = Book.find("byExternalId", externalId).first();
		if (book == null) {
			JsonElement jsonElement = getExternalResource(GOOGLE_BOOK + externalId);
			book = parseBook(jsonElement);
		}
		return book;
	}

	private static List<BookResource> getBestBooksList(Integer page, Integer size) {
		size = (size == null) ? 9 : size;
		page = (page == null) ? 0 : page;

		List<Book> books = Book.find("order by readers.size desc").fetch(page + 1, size);

		List<BookResource> resources = new ArrayList<BookResource>();
		for (Book book : books) {
			resources.add(new BookResource(book));
		}

		return resources;
	}
	
	private static List<BookResource> getBooksList(Integer page, Integer size) {
		size = (size == null) ? 9 : size;
		page = (page == null) ? 0 : page;

		List<Book> books = Book.find("order by insertedAt asc").fetch(page + 1, size);

		List<BookResource> resources = new ArrayList<BookResource>();
		for (Book book : books) {
			resources.add(new BookResource(book));
		}

		return resources;
	}

	private static List<BookResource> getBooks(String query) {
		JsonElement jsonElement = getExternalResource(GOOGLE_BOOK + "?startIndex=0&maxResults=9&q=" + query.replaceAll("\\s", "+"));

		List<BookResource> list = new ArrayList<BookResource>();
		Iterator<JsonElement> iterator = jsonElement.getAsJsonObject().getAsJsonArray("items").iterator();
		while (iterator.hasNext()) {
			list.add(parseBookResource(iterator.next()));
		}

		return list;
	}

	private static JsonElement getExternalResource(String externalUrl) {
	    Logger.debug("Making an external call to %s", externalUrl);
		F.Promise<WS.HttpResponse> f = WS.url(externalUrl).getAsync();
		Promise<HttpResponse> promise = F.Promise.waitAny(f);

		HttpResponse httpResponse = null;
		try {
			httpResponse = promise.get();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return httpResponse.getJson();
	}

	private static BookResource parseBookResource(JsonElement element) {
		Book book = parseBook(element);

		return new BookResource(book);
	}

	private static Book parseBook(JsonElement element) {
		String title = "";
		String authorName = "";
		String thumbnailUrl = "https://books.google.de/googlebooks/images/no_cover_thumb.gif";
		String smallThumbnailUrl = "https://books.google.de/googlebooks/images/no_cover_thumb.gif";

		JsonObject volumeInfo = element.getAsJsonObject().getAsJsonObject("volumeInfo").getAsJsonObject();

		title = volumeInfo.getAsJsonPrimitive("title").getAsString();
		JsonArray authorsArray = volumeInfo.getAsJsonArray("authors");
		if (authorsArray != null) {
			authorName = volumeInfo.getAsJsonArray("authors").get(0).getAsString();
		}
		JsonObject imageLinks = volumeInfo.getAsJsonObject("imageLinks");
		if (imageLinks != null) {
			thumbnailUrl = imageLinks.getAsJsonObject().getAsJsonPrimitive("thumbnail").getAsString();
			smallThumbnailUrl = imageLinks.getAsJsonObject().getAsJsonPrimitive("smallThumbnail").getAsString();
		}

		String externalBookId = element.getAsJsonObject().getAsJsonPrimitive("id").getAsString();
		JsonPrimitive descriptionObject = volumeInfo.getAsJsonPrimitive("description");
		String description = null;
		if(descriptionObject != null){
			description = volumeInfo.getAsJsonPrimitive("description").getAsString();
		}

		Book book = new Book();
		book.externalId = externalBookId;
		book.title = title;
		book.description = description;
		book.authorName = authorName;
		book.thumbnailUrl = thumbnailUrl;
		book.smallThumbnailUrl = smallThumbnailUrl;
		return book;
	}
}
