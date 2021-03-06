/*
 * Javassist, a Java-bytecode translator toolkit.
 * Copyright (C) 1999- Shigeru Chiba. All Rights Reserved.
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License.  Alternatively, the contents of this file may be used under
 * the terms of the GNU Lesser General Public License Version 2.1 or later,
 * or the Apache License Version 2.0.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 */
package org.hotswap.agent.javassist.bytecode.analysis;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.hotswap.agent.javassist.CtClass;

/**
 * MultiType represents an unresolved type. Whenever two <literal>Type</literal>
 * instances are merged, if they share more than one super type (either an
 * interface or a superclass), then a <literal>MultiType</literal> is used to
 * represent the possible super types. The goal of a
 * <literal>MultiType</literal> is to reduce the set of possible types down to a
 * single resolved type. This is done by eliminating non-assignable types from
 * the typeset when the <literal>MultiType</literal> is passed as an argument to
 * {@link Type#isAssignableFrom(Type)}, as well as removing non-intersecting
 * types during a merge.
 * <p/>
 * Note: Currently the <litera>MultiType</literal> instance is reused as much as
 * possible so that updates are visible from all frames. In addition, all
 * <literal>MultiType</literal> merge paths are also updated. This is somewhat
 * hackish, but it appears to handle most scenarios.
 *
 * @author Jason T. Greene
 */

/*
 * TODO - A better, but more involved, approach would be to track the
 * instruction offset that resulted in the creation of this type, and whenever
 * the typeset changes, to force a merge on that position. This would require
 * creating a new MultiType instance every time the typeset changes, and somehow
 * communicating assignment changes to the Analyzer
 */
public class MultiType extends Type {
	private Map<Object, CtClass> interfaces;
	private Type resolved;
	private Type potentialClass;
	private MultiType mergeSource;
	private boolean changed = false;

	public MultiType(Map<Object, CtClass> interfaces) {
		this(interfaces, null);
	}

	public MultiType(Map<Object, CtClass> interfaces, Type potentialClass) {
		super(null);
		this.interfaces = interfaces;
		this.potentialClass = potentialClass;
	}

	/**
	 * Gets the class that corresponds with this type. If this information is
	 * not yet known, java.lang.Object will be returned.
	 */
	@Override
	public org.hotswap.agent.javassist.CtClass getCtClass() {
		if (resolved != null) {
			return resolved.getCtClass();
		}

		return Type.OBJECT.getCtClass();
	}

	/**
	 * Always returns null since this type is never used for an array.
	 */
	@Override
	public Type getComponent() {
		return null;
	}

	/**
	 * Always returns 1, since this type is a reference.
	 */
	@Override
	public int getSize() {
		return 1;
	}

	/**
	 * Always reutnrs false since this type is never used for an array
	 */
	@Override
	public boolean isArray() {
		return false;
	}

	/**
	 * Returns true if the internal state has changed.
	 */
	@Override
	boolean popChanged() {
		boolean changed = this.changed;
		this.changed = false;
		return changed;
	}

	@Override
	public boolean isAssignableFrom(Type type) {
		throw new UnsupportedOperationException("Not implemented");
	}

	public boolean isAssignableTo(Type type) {
		if (resolved != null) {
			return type.isAssignableFrom(resolved);
		}

		if (Type.OBJECT.equals(type)) {
			return true;
		}

		if (potentialClass != null && !type.isAssignableFrom(potentialClass)) {
			potentialClass = null;
		}

		Map<Object, CtClass> map = mergeMultiAndSingle(this, type);

		if (map.size() == 1 && potentialClass == null) {
			// Update previous merge paths to the same resolved type
			resolved = Type.get(map.values().iterator().next());
			propogateResolved();

			return true;
		}

		// Keep all previous merge paths up to date
		if (map.size() >= 1) {
			interfaces = map;
			propogateState();

			return true;
		}

		if (potentialClass != null) {
			resolved = potentialClass;
			propogateResolved();

			return true;
		}

		return false;
	}

	private void propogateState() {
		MultiType source = mergeSource;
		while (source != null) {
			source.interfaces = interfaces;
			source.potentialClass = potentialClass;
			source = source.mergeSource;
		}
	}

	private void propogateResolved() {
		MultiType source = mergeSource;
		while (source != null) {
			source.resolved = resolved;
			source = source.mergeSource;
		}
	}

	/**
	 * Always returns true, since this type is always a reference.
	 *
	 * @return true
	 */
	@Override
	public boolean isReference() {
		return true;
	}

	private Map<Object, CtClass> getAllMultiInterfaces(MultiType type) {
		Map<Object, CtClass> map = new HashMap<Object, CtClass>();

		Iterator<CtClass> iter = type.interfaces.values().iterator();
		while (iter.hasNext()) {
			org.hotswap.agent.javassist.CtClass intf = iter.next();
			map.put(intf.getName(), intf);
			getAllInterfaces(intf, map);
		}

		return map;
	}

	private Map<Object, CtClass> mergeMultiInterfaces(MultiType type1, MultiType type2) {
		Map<Object, CtClass> map1 = getAllMultiInterfaces(type1);
		Map<Object, CtClass> map2 = getAllMultiInterfaces(type2);

		return findCommonInterfaces(map1, map2);
	}

	private Map<Object, CtClass> mergeMultiAndSingle(MultiType multi, Type single) {
		Map<Object, CtClass> map1 = getAllMultiInterfaces(multi);
		Map<Object, CtClass> map2 = getAllInterfaces(single.getCtClass(), null);

		return findCommonInterfaces(map1, map2);
	}

	private boolean inMergeSource(MultiType source) {
		while (source != null) {
			if (source == this) {
				return true;
			}

			source = source.mergeSource;
		}

		return false;
	}

	@Override
	public Type merge(Type type) {
		if (this == type) {
			return this;
		}

		if (type == UNINIT) {
			return this;
		}

		if (type == BOGUS) {
			return BOGUS;
		}

		if (type == null) {
			return this;
		}

		if (resolved != null) {
			return resolved.merge(type);
		}

		if (potentialClass != null) {
			Type mergePotential = potentialClass.merge(type);
			if (!mergePotential.equals(potentialClass) || mergePotential.popChanged()) {
				potentialClass = Type.OBJECT.equals(mergePotential) ? null : mergePotential;
				changed = true;
			}
		}

		Map<Object, CtClass> merged;

		if (type instanceof MultiType) {
			MultiType multi = (MultiType) type;

			if (multi.resolved != null) {
				merged = mergeMultiAndSingle(this, multi.resolved);
			} else {
				merged = mergeMultiInterfaces(multi, this);
				if (!inMergeSource(multi)) {
					mergeSource = multi;
				}
			}
		} else {
			merged = mergeMultiAndSingle(this, type);
		}

		// Keep all previous merge paths up to date
		if (merged.size() > 1 || merged.size() == 1 && potentialClass != null) {
			// Check for changes
			if (merged.size() != interfaces.size()) {
				changed = true;
			} else if (changed == false) {
				Iterator<Object> iter = merged.keySet().iterator();
				while (iter.hasNext()) {
					if (!interfaces.containsKey(iter.next())) {
						changed = true;
					}
				}
			}

			interfaces = merged;
			propogateState();

			return this;
		}

		if (merged.size() == 1) {
			resolved = Type.get(merged.values().iterator().next());
		} else if (potentialClass != null) {
			resolved = potentialClass;
		} else {
			resolved = OBJECT;
		}

		propogateResolved();

		return resolved;
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof MultiType)) {
			return false;
		}

		MultiType multi = (MultiType) o;
		if (resolved != null) {
			return resolved.equals(multi.resolved);
		} else if (multi.resolved != null) {
			return false;
		}

		return interfaces.keySet().equals(multi.interfaces.keySet());
	}

	@Override
	public String toString() {
		if (resolved != null) {
			return resolved.toString();
		}

		StringBuffer buffer = new StringBuffer("{");
		Iterator<Object> iter = interfaces.keySet().iterator();
		while (iter.hasNext()) {
			buffer.append(iter.next());
			buffer.append(", ");
		}
		buffer.setLength(buffer.length() - 2);
		if (potentialClass != null) {
			buffer.append(", *").append(potentialClass.toString());
		}
		buffer.append("}");
		return buffer.toString();
	}
}
