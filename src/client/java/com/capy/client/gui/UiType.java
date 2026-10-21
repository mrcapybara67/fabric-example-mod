package com.capy.client.gui;

/**
 * The set of typefaces the interface uses.
 *
 * Keeping the four weights behind one object means a component can ask for
 * "the title face" without knowing how it is produced, and the offline
 * renderer can supply the same four weights from the bundled Inter files.
 */
public interface UiType {
	/** Body text: descriptions, values, hints. */
	UiText regular();

	/** Module names, category labels, emphasised values. */
	UiText medium();

	/** Section headings and the wordmark. */
	UiText bold();

	/** Large display cut used for the wordmark and alert headlines. */
	UiText display();
}
