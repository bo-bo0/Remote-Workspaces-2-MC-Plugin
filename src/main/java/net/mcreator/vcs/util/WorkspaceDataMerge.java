/*
 * MCreator VCS plugin
 * Copyright (C) 2026, bo bo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.mcreator.vcs.util;

import com.google.gson.*;

import java.util.*;

class WorkspaceDataMerge {

	static JsonObject merge(JsonObject base, JsonObject local, JsonObject remote, JsonObject resolved) {
		return merge(base, local, remote, resolved, "", new LinkedHashSet<>()).getAsJsonObject();
	}

	static Set<String> conflictingFields(JsonObject base, JsonObject local, JsonObject remote) {
		Set<String> conflicts = new LinkedHashSet<>();
		merge(base, local, remote, base, "", conflicts);
		return conflicts;
	}

	private static JsonElement merge(JsonElement base, JsonElement local, JsonElement remote, JsonElement resolved,
			String field, Set<String> conflicts) {
		if (Objects.equals(local, remote))
			return copy(local);
		if (Objects.equals(base, local))
			return copy(remote);
		if (Objects.equals(base, remote))
			return copy(local);
		if (local instanceof JsonObject left && remote instanceof JsonObject right
				&& (base == null || base instanceof JsonObject)) {
			JsonObject result = new JsonObject();
			Set<String> keys = new LinkedHashSet<>();
			if (base instanceof JsonObject old)
				keys.addAll(old.keySet());
			keys.addAll(left.keySet());
			keys.addAll(right.keySet());
			for (String key : keys) {
				JsonElement value = merge(property(base, key), left.get(key), right.get(key), property(resolved, key),
						field.isEmpty() ? key : field, conflicts);
				if (value != null)
					result.add(key, value);
			}
			return result;
		}
		if (local instanceof JsonArray left && remote instanceof JsonArray right
				&& base instanceof JsonArray old && named(left) && named(right) && named(old)) {
			Map<String, JsonElement> before = indexed(old), mine = indexed(left), theirs = indexed(right);
			Map<String, JsonElement> choices = resolved instanceof JsonArray array && named(array)
					? indexed(array) : Map.of();
			Set<String> keys = new LinkedHashSet<>(mine.keySet());
			keys.addAll(theirs.keySet());
			keys.addAll(before.keySet());
			JsonArray result = new JsonArray();
			for (String key : keys) {
				JsonElement value = merge(before.get(key), mine.get(key), theirs.get(key), choices.get(key), field, conflicts);
				if (value != null)
					result.add(value);
			}
			return result;
		}
		conflicts.add(field);
		return copy(resolved);
	}

	private static JsonElement property(JsonElement object, String key) {
		return object instanceof JsonObject json ? json.get(key) : null;
	}

	private static JsonElement copy(JsonElement value) { return value == null ? null : value.deepCopy(); }

	private static boolean named(JsonArray array) {
		Set<String> keys = new HashSet<>();
		for (JsonElement value : array) {
			if (!(value instanceof JsonObject object) || !object.has("name")
					|| !(object.get("name") instanceof JsonPrimitive name) || !name.isString()
					|| !keys.add(name.getAsString()))
				return false;
		}
		return true;
	}

	private static Map<String, JsonElement> indexed(JsonArray array) {
		Map<String, JsonElement> result = new LinkedHashMap<>();
		array.forEach(value -> result.put(value.getAsJsonObject().get("name").getAsString(), value));
		return result;
	}
}
