package com.capy.client.finder.core;

public interface DrawSink {
	void quad(double x0, double y0, double z0, double x1, double y1, double z1,
			double x2, double y2, double z2, double x3, double y3, double z3, int argb);

	void line(double x0, double y0, double z0, double x1, double y1, double z1, float width, int argb);
}
