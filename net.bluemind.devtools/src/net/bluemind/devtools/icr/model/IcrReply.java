package net.bluemind.devtools.icr.model;

/**
 * One message in an {@link IcrThread} conversation. Either the user's
 * follow-up ({@code author == "user"}) or Claude's answer
 * ({@code author == "claude"}).
 */
public final class IcrReply {

	public static final String AUTHOR_USER = "user";
	public static final String AUTHOR_CLAUDE = "claude";

	private final String id;
	private final String author;
	private final String body;
	private final long createdAt;

	public IcrReply(String id, String author, String body, long createdAt) {
		this.id = id;
		this.author = author;
		this.body = body;
		this.createdAt = createdAt;
	}

	public String id() {
		return id;
	}

	public String author() {
		return author;
	}

	public String body() {
		return body;
	}

	public long createdAt() {
		return createdAt;
	}
}
