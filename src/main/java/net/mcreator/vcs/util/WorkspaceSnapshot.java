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

import com.google.gson.JsonObject;
import net.mcreator.element.ModElementType;
import net.mcreator.generator.Generator;
import net.mcreator.workspace.Workspace;
import net.mcreator.workspace.WorkspaceFileManager;
import net.mcreator.workspace.elements.ModElement;
import net.mcreator.workspace.elements.SoundElement;
import net.mcreator.workspace.elements.VariableElement;

import java.util.*;

class WorkspaceSnapshot extends Workspace {

	private final transient List<ModElement> elements;
	private final transient Set<VariableElement> variables;
	private final transient Set<SoundElement> sounds;
	private final transient Map<String, LinkedHashMap<String, String>> languages = new LinkedHashMap<>();

	WorkspaceSnapshot(Workspace original, Workspace data, List<Generator> generators) {
		super(null);
		loadStoredDataFrom(data);
		elements = new ArrayList<>(data.getModElements());
		variables = new LinkedHashSet<>(data.getVariableElements());
		sounds = new LinkedHashSet<>(data.getSoundElements());
		data.getLanguageMap().forEach((key, value) -> languages.put(key, new LinkedHashMap<>(value)));
		fileManager = original.getFileManager();
		generator = new Generator(this);
		generators.add(generator);
		generator.setGradleCache(original.getGenerator().getGradleCache());
		reloadModElements();
		reloadFolderStructure();
	}

	Workspace storedData() {
		JsonObject json = WorkspaceFileManager.gson.toJsonTree(this, Workspace.class).getAsJsonObject();
		json.add("mod_elements", WorkspaceFileManager.gson.toJsonTree(elements));
		json.add("variable_elements", WorkspaceFileManager.gson.toJsonTree(variables));
		json.add("sound_elements", WorkspaceFileManager.gson.toJsonTree(sounds));
		json.add("language_map", WorkspaceFileManager.gson.toJsonTree(languages));
		return WorkspaceFileManager.gson.fromJson(json, Workspace.class);
	}

	@Override public Collection<ModElement> getModElements() { return List.copyOf(elements); }
	@Override public Collection<VariableElement> getVariableElements() { return List.copyOf(variables); }
	@Override public Collection<SoundElement> getSoundElements() { return List.copyOf(sounds); }
	@Override public Map<String, LinkedHashMap<String, String>> getLanguageMap() { return languages; }
	@Override public ModElement getModElementByName(String name) {
		return elements.stream().filter(e -> e.getName().equals(name)).findFirst().orElse(null);
	}
	@Override public boolean containsModElement(String name) { return getModElementByName(name) != null; }
	@Override public boolean containsType(ModElementType<?> type) { return elements.stream().anyMatch(e -> e.getType() == type); }
	@Override public Collection<ModElement> getModElementsByType(ModElementType<?> type) {
		return elements.stream().filter(e -> e.getType() == type).toList();
	}
	@Override public VariableElement getVariableElementByName(String name) {
		return variables.stream().filter(e -> e.getName().equals(name)).findFirst().orElse(null);
	}
	@Override public void addModElement(ModElement element) {
		if (!elements.contains(element)) {
			ModElement copy = WorkspaceFileManager.gson.fromJson(WorkspaceFileManager.gson.toJson(element), ModElement.class);
			copy.reinit(this);
			elements.add(copy);
		}
	}
	@Override public void removeModElement(ModElement element) { elements.remove(element); }
	@Override public void addVariableElement(VariableElement element) { variables.add(element); }
	@Override public void removeVariableElement(VariableElement element) { variables.remove(element); }
	@Override public void addSoundElement(SoundElement element) { sounds.add(element); }
	@Override public void removeSoundElement(SoundElement element) { sounds.remove(element); }
	@Override public void addLanguage(String language, LinkedHashMap<String, String> data) {
		languages.putIfAbsent(language, new LinkedHashMap<>(data));
	}
	@Override public void updateLanguage(String language, LinkedHashMap<String, String> data) {
		languages.put(language, new LinkedHashMap<>(data));
	}
	@Override public void removeLocalizationLanguage(String language) {
		if (!language.equals("en_us"))
			languages.remove(language);
	}
}
