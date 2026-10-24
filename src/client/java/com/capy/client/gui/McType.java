package com.capy.client.gui;

/**
 * {@link UiType} backed by the bundled Inter faces.
 *
 * The faces are resolved lazily, so creating this object is cheap and safe at
 * any point during startup.
 */
public final class McType implements UiType {
	private final UiText regular;
	private final UiText medium;
	private final UiText bold;
	private final UiText display;
	private final UiText headline;

	public McType() {
		this.regular = new McText(UiFonts::regular);
		this.medium = new McText(UiFonts::medium);
		this.bold = new McText(UiFonts::bold);
		this.display = new McText(UiFonts::display);
		this.headline = new McText(UiFonts::headline);
	}

	@Override
	public UiText regular() {
		return regular;
	}

	@Override
	public UiText medium() {
		return medium;
	}

	@Override
	public UiText bold() {
		return bold;
	}

	@Override
	public UiText display() {
		return display;
	}

	@Override
	public UiText headline() {
		return headline;
	}
}
