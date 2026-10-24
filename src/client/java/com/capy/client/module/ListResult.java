package com.capy.client.module;

/**
 * Result of adding an entry to one of the client's list settings.
 *
 * It lives outside the settings themselves so every list — watched usernames,
 * ESP block filters — answers its editor with the same vocabulary, and the
 * editor owns the wording instead of each setting inventing its own.
 */
public enum ListResult {
	/** The entry was accepted and is now part of the list. */
	ADDED,
	/** The entry is not syntactically valid for this list. */
	INVALID,
	/** The entry is already in the list. */
	DUPLICATE,
	/** The list is already at its maximum size. */
	FULL
}
