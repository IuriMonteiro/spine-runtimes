
package com.esotericsoftware.spine;

import static com.badlogic.gdx.math.MathUtils.*;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import com.esotericsoftware.spine.attachments.Attachment;
import com.esotericsoftware.spine.attachments.PathAttachment;

public class PathConstraint implements Updatable {
	static private final int NONE = -1;
	static private final int BEFORE = -2;
	static private final int AFTER = -3;

	final PathConstraintData data;
	final Array<Bone> bones;
	Slot target;
	float position, rotateMix, translateMix, scaleMix;

	final FloatArray spacing = new FloatArray(), positions = new FloatArray(), temp = new FloatArray();

	public PathConstraint (PathConstraintData data, Skeleton skeleton) {
		if (data == null) throw new IllegalArgumentException("data cannot be null.");
		if (skeleton == null) throw new IllegalArgumentException("skeleton cannot be null.");
		this.data = data;
		position = data.position;
		rotateMix = data.rotateMix;
		translateMix = data.translateMix;
		scaleMix = data.scaleMix;
		bones = new Array(data.bones.size);
		for (BoneData boneData : data.bones)
			bones.add(skeleton.findBone(boneData.name));
		target = skeleton.findSlot(data.target.name);
	}

	/** Copy constructor. */
	public PathConstraint (PathConstraint constraint, Skeleton skeleton) {
		if (constraint == null) throw new IllegalArgumentException("constraint cannot be null.");
		if (skeleton == null) throw new IllegalArgumentException("skeleton cannot be null.");
		data = constraint.data;
		bones = new Array(constraint.bones.size);
		for (Bone bone : constraint.bones)
			bones.add(skeleton.bones.get(bone.data.index));
		target = skeleton.slots.get(constraint.target.data.index);
		position = constraint.position;
		rotateMix = constraint.rotateMix;
		translateMix = constraint.translateMix;
		scaleMix = constraint.scaleMix;
	}

	public void apply () {
		update();
	}

	public void update () {
		Attachment attachment = target.getAttachment();
		if (!(attachment instanceof PathAttachment)) return;

		float rotateMix = this.rotateMix, translateMix = this.translateMix, scaleMix = this.scaleMix;
		boolean translate = translateMix > 0, rotate = rotateMix > 0, scale = scaleMix > 0;
		if (!translate && !rotate && !scale) return;

		PathAttachment path = (PathAttachment)attachment;
		FloatArray spacing = this.spacing;
		spacing.clear();
		spacing.add(0);

		Array<Bone> bones = this.bones;
		int boneCount = bones.size;
		if (boneCount == 1) {
			float[] positions = computeWorldPositions(path, rotate);
			Bone bone = bones.first();
			bone.worldX += (positions[0] - bone.worldX) * translateMix;
			bone.worldY += (positions[1] - bone.worldY) * translateMix;
			if (rotate) {
				float a = bone.a, b = bone.b, c = bone.c, d = bone.d;
				float r = positions[2] - atan2(c, a) + data.offsetRotation * degRad;
				if (r > PI)
					r -= PI2;
				else if (r < -PI) r += PI2;
				r *= rotateMix;
				float cos = cos(r), sin = sin(r);
				bone.a = cos * a - sin * c;
				bone.b = cos * b - sin * d;
				bone.c = sin * a + cos * c;
				bone.d = sin * b + cos * d;
			}
			return;
		}

		for (int i = 0; i < boneCount; i++) {
			Bone bone = bones.get(i);
			float length = bone.data.length, x = length * bone.a, y = length * bone.c;
			spacing.add((float)Math.sqrt(x * x + y * y));
		}
		float[] positions = computeWorldPositions(path, false);

		float boneX = positions[0], boneY = positions[1], offsetRotation = data.offsetRotation;
		for (int i = 0, p = 2; i < boneCount; i++, p += 2) {
			Bone bone = bones.get(i);
			bone.worldX += (boneX - bone.worldX) * translateMix;
			bone.worldY += (boneY - bone.worldY) * translateMix;
			float x = positions[p], y = positions[p + 1], dx = x - boneX, dy = y - boneY;
			if (scale) {
				float space = spacing.get(i + 1);
				if (space != 0) {
					float s = ((float)Math.sqrt(dx * dx + dy * dy) / space - 1) * scaleMix + 1;
					bone.a *= s;
					bone.c *= s;
				}
			}
			if (!rotate) {
				boneX = x;
				boneY = y;
			} else {
				float a = bone.a, b = bone.b, c = bone.c, d = bone.d;
				float r = atan2(dy, dx) - atan2(c, a) + offsetRotation * degRad, cos, sin;
				if (offsetRotation != 0) {
					boneX = x;
					boneY = y;
				} else { // Mix between on path and at tip.
					cos = cos(r);
					sin = sin(r);
					float length = bone.data.length;
					boneX = x + (length * (cos * a - sin * c) - dx) * rotateMix;
					boneY = y + (length * (sin * a + cos * c) - dy) * rotateMix;
				}
				if (r > PI)
					r -= PI2;
				else if (r < -PI) //
					r += PI2;
				r *= rotateMix;
				cos = cos(r);
				sin = sin(r);
				bone.a = cos * a - sin * c;
				bone.b = cos * b - sin * d;
				bone.c = sin * a + cos * c;
				bone.d = sin * b + cos * d;
			}
		}
	}

	private float[] computeWorldPositions (PathAttachment path, boolean tangents) {
		Slot target = this.target;
		float position = this.position;
		int spacingCount = spacing.size;
		float[] spacing = this.spacing.items;
		FloatArray positions = this.positions;
		positions.clear();
		boolean closed = path.getClosed();
		int verticesLength = path.getWorldVerticesLength(), curves = verticesLength / 6;
		float[] temp;
		int lastCurve = NONE;

		// New.
		if (!path.getConstantSpeed()) {
			if (!closed) curves--;
			float[] curveLengths = path.getCurveLengths().items;
			float pathLength = path.getTotalLength();
			position *= pathLength;
			temp = this.temp.setSize(8);
			for (int i = 0, curve = 0; i < spacingCount; i++) {
				position += spacing[i];
				float p = position;

				if (closed) {
					p %= 1;
					if (p < 0) p += 1;
				} else if (p < 0) {
					if (lastCurve != BEFORE) {
						lastCurve = BEFORE;
						path.computeWorldVertices(target, 2, 4, temp, 0);
					}
					addBeforePosition(p, temp, 0, positions, tangents);
					continue;
				} else if (p > pathLength) {
					if (lastCurve != AFTER) {
						lastCurve = AFTER;
						path.computeWorldVertices(target, verticesLength - 6, 4, temp, 0);
					}
					addAfterPosition(p - pathLength, temp, 0, positions, tangents);
					continue;
				}

				// Determine curve containing position.
				for (;; curve++) {
					float length = curveLengths[curve];
					if (p > length) continue;
					if (curve == 0)
						p /= length;
					else {
						float prev = curveLengths[curve - 1];
						p = (p - prev) / (length - prev);
					}
					break;
				}

				if (curve != lastCurve) {
					lastCurve = curve;
					if (closed && curve == curves - 1) {
						path.computeWorldVertices(target, verticesLength - 4, 4, temp, 0);
						path.computeWorldVertices(target, 0, 4, temp, 4);
					} else
						path.computeWorldVertices(target, curve * 6 + 2, 8, temp, 0);
				}
				addCurvePosition(p, temp[0], temp[1], temp[2], temp[3], temp[4], temp[5], temp[6], temp[7], positions, tangents);
			}
			return positions.items;
		}

		// World vertices, verticesStart to verticesStart + verticesLength - 1.
		int verticesStart = 10 + curves;
		temp = this.temp.setSize(verticesStart + verticesLength + 2);
		if (closed) {
			verticesLength += 2;
			int verticesEnd = verticesStart + verticesLength;
			path.computeWorldVertices(target, 2, verticesLength - 4, temp, verticesStart);
			path.computeWorldVertices(target, 0, 2, temp, verticesEnd - 4);
			temp[verticesEnd - 2] = temp[verticesStart];
			temp[verticesEnd - 1] = temp[verticesStart + 1];
		} else {
			verticesStart--;
			verticesLength -= 4;
			path.computeWorldVertices(target, 2, verticesLength, temp, verticesStart);
		}

		// Curve lengths, 10 to verticesStart - 1.
		float pathLength = 0;
		float x1 = temp[verticesStart], y1 = temp[verticesStart + 1], cx1 = 0, cy1 = 0, cx2 = 0, cy2 = 0, x2 = 0, y2 = 0;
		float tmpx, tmpy, dddfx, dddfy, ddfx, ddfy, dfx, dfy;
		for (int i = 10, v = verticesStart + 2; i < verticesStart; i++, v += 6) {
			cx1 = temp[v];
			cy1 = temp[v + 1];
			cx2 = temp[v + 2];
			cy2 = temp[v + 3];
			x2 = temp[v + 4];
			y2 = temp[v + 5];
			tmpx = (x1 - cx1 * 2 + cx2) * 0.1875f;
			tmpy = (y1 - cy1 * 2 + cy2) * 0.1875f;
			dddfx = ((cx1 - cx2) * 3 - x1 + x2) * 0.09375f;
			dddfy = ((cy1 - cy2) * 3 - y1 + y2) * 0.09375f;
			ddfx = tmpx * 2 + dddfx;
			ddfy = tmpy * 2 + dddfy;
			dfx = (cx1 - x1) * 0.75f + tmpx + dddfx * 0.16666667f;
			dfy = (cy1 - y1) * 0.75f + tmpy + dddfy * 0.16666667f;
			pathLength += (float)Math.sqrt(dfx * dfx + dfy * dfy);
			dfx += ddfx;
			dfy += ddfy;
			ddfx += dddfx;
			ddfy += dddfy;
			pathLength += (float)Math.sqrt(dfx * dfx + dfy * dfy);
			dfx += ddfx;
			dfy += ddfy;
			pathLength += (float)Math.sqrt(dfx * dfx + dfy * dfy);
			dfx += ddfx + dddfx;
			dfy += ddfy + dddfy;
			pathLength += (float)Math.sqrt(dfx * dfx + dfy * dfy);
			temp[i] = pathLength;
			x1 = x2;
			y1 = y2;
		}
		position *= pathLength;

		float curveLength = 0;
		for (int i = 0, curve = 10, segment = 0; i < spacingCount; i++) {
			position += spacing[i];
			float p = position;

			if (closed) {
				p %= pathLength;
				if (p < 0) p += pathLength;
			} else if (p < 0) {
				addBeforePosition(p, temp, verticesStart, positions, tangents);
				continue;
			} else if (p > pathLength) {
				addAfterPosition(p - pathLength, temp, verticesStart + verticesLength - 4, positions, tangents);
				continue;
			}

			// Determine curve containing position.
			for (;; curve++) {
				float length = temp[curve];
				if (p > length) continue;
				if (curve == 10)
					p /= length;
				else {
					float prev = temp[curve - 1];
					p = (p - prev) / (length - prev);
				}
				break;
			}

			// Curve segment lengths, 0 to 9.
			if (curve != lastCurve) {
				lastCurve = curve;
				int index = verticesStart + (curve - 10) * 6;
				x1 = temp[index];
				y1 = temp[index + 1];
				cx1 = temp[index + 2];
				cy1 = temp[index + 3];
				cx2 = temp[index + 4];
				cy2 = temp[index + 5];
				x2 = temp[index + 6];
				y2 = temp[index + 7];
				tmpx = (x1 - cx1 * 2 + cx2) * 0.03f;
				tmpy = (y1 - cy1 * 2 + cy2) * 0.03f;
				dddfx = ((cx1 - cx2) * 3 - x1 + x2) * 0.006f;
				dddfy = ((cy1 - cy2) * 3 - y1 + y2) * 0.006f;
				ddfx = tmpx * 2 + dddfx;
				ddfy = tmpy * 2 + dddfy;
				dfx = (cx1 - x1) * 0.3f + tmpx + dddfx * 0.16666667f;
				dfy = (cy1 - y1) * 0.3f + tmpy + dddfy * 0.16666667f;
				curveLength = (float)Math.sqrt(dfx * dfx + dfy * dfy);
				temp[0] = curveLength;
				for (int ii = 1; ii < 8; ii++) {
					dfx += ddfx;
					dfy += ddfy;
					ddfx += dddfx;
					ddfy += dddfy;
					curveLength += (float)Math.sqrt(dfx * dfx + dfy * dfy);
					temp[ii] = curveLength;
				}
				dfx += ddfx;
				dfy += ddfy;
				curveLength += (float)Math.sqrt(dfx * dfx + dfy * dfy);
				temp[8] = curveLength;
				dfx += ddfx + dddfx;
				dfy += ddfy + dddfy;
				curveLength += (float)Math.sqrt(dfx * dfx + dfy * dfy);
				temp[9] = curveLength;
				segment = 0;
			}

			// Weight by segment length.
			p *= curveLength;
			for (;; segment++) {
				float length = temp[segment];
				if (p > length) continue;
				if (segment == 0)
					p = 0.1f * p / length;
				else {
					float prev = temp[segment - 1];
					p = 0.1f * (segment + (p - prev) / (length - prev));
				}
				break;
			}

			addCurvePosition(p, x1, y1, cx1, cy1, cx2, cy2, x2, y2, positions, tangents);
		}

		return positions.items;
	}

	private void addBeforePosition (float p, float[] temp, int i, FloatArray out, boolean tangents) {
		float x1 = temp[i], y1 = temp[i + 1], dx = temp[i + 2] - x1, dy = temp[i + 3] - y1, r = atan2(dy, dx);
		out.add(x1 + p * cos(r));
		out.add(y1 + p * sin(r));
		if (tangents) out.add(r + PI);
	}

	private void addAfterPosition (float p, float[] temp, int i, FloatArray out, boolean tangents) {
		float x1 = temp[i + 2], y1 = temp[i + 3], dx = x1 - temp[i], dy = y1 - temp[i + 1], r = atan2(dy, dx);
		out.add(x1 + p * cos(r));
		out.add(y1 + p * sin(r));
		if (tangents) out.add(r + PI);
	}

	private void addCurvePosition (float p, float x1, float y1, float cx1, float cy1, float cx2, float cy2, float x2, float y2,
		FloatArray out, boolean tangents) {
		if (p == 0) p = 0.0001f;
		float tt = p * p, ttt = tt * p, u = 1 - p, uu = u * u, uuu = uu * u;
		float ut = u * p, ut3 = ut * 3, uut3 = u * ut3, utt3 = ut3 * p;
		float x = x1 * uuu + cx1 * uut3 + cx2 * utt3 + x2 * ttt, y = y1 * uuu + cy1 * uut3 + cy2 * utt3 + y2 * ttt;
		out.add(x);
		out.add(y);
		if (tangents) out.add(atan2(y - (y1 * uu + cy1 * ut * 2 + cy2 * tt), x - (x1 * uu + cx1 * ut * 2 + cx2 * tt)));
	}

	public float getPosition () {
		return position;
	}

	public void setPosition (float position) {
		this.position = position;
	}

	public float getRotateMix () {
		return rotateMix;
	}

	public void setRotateMix (float rotateMix) {
		this.rotateMix = rotateMix;
	}

	public float getTranslateMix () {
		return translateMix;
	}

	public void setTranslateMix (float translateMix) {
		this.translateMix = translateMix;
	}

	public float getScaleMix () {
		return scaleMix;
	}

	public void setScaleMix (float scaleMix) {
		this.scaleMix = scaleMix;
	}

	public Array<Bone> getBones () {
		return bones;
	}

	public Slot getTarget () {
		return target;
	}

	public void setTarget (Slot target) {
		this.target = target;
	}

	public PathConstraintData getData () {
		return data;
	}

	public String toString () {
		return data.name;
	}
}