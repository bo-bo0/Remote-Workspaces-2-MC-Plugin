package net.mcreator.vcs.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceDataMergeTest {
	@Test void independentSettingsMetadataTagsAndOrderArePreserved() {
		JsonObject base = json("{settings:{a:0,b:0},metadata:{x:0,y:0},tags:[],order:['a']}");
		JsonObject local = json("{settings:{a:1,b:0},metadata:{x:1,y:0},tags:[],order:['a']}");
		JsonObject remote = json("{settings:{a:0,b:2},metadata:{x:0,y:2},tags:['tag'],order:['a','b']}");
		assertTrue(WorkspaceDataMerge.conflictingFields(base, local, remote).isEmpty());
		assertEquals(json("{settings:{a:1,b:2},metadata:{x:1,y:2},tags:['tag'],order:['a','b']}"),
				WorkspaceDataMerge.merge(base, local, remote, base));
	}

	@Test void namedCollectionsPreserveIndependentModificationsAndChosenConflicts() {
		JsonObject base = json("{items:[{name:'a',value:0},{name:'b',value:0}]}");
		JsonObject local = json("{items:[{name:'a',value:1},{name:'b',value:0},{name:'c',value:3}]}");
		JsonObject remote = json("{items:[{name:'a',value:2},{name:'b',value:2}]}");
		assertEquals(Set.of("items"), WorkspaceDataMerge.conflictingFields(base, local, remote));
		assertEquals(json("{items:[{name:'a',value:2},{name:'b',value:2},{name:'c',value:3}]}"),
				WorkspaceDataMerge.merge(base, local, remote, remote));
	}

	@Test void deletionsAndUnsupportedConcurrentChangesRequireExplicitResolution() {
		JsonObject base = json("{metadata:{keep:1,remove:1},order:['a']}");
		JsonObject local = json("{metadata:{keep:1},order:['a','b']}");
		JsonObject remote = json("{metadata:{keep:2,remove:1},order:['a','c']}");
		assertEquals(Set.of("order"), WorkspaceDataMerge.conflictingFields(base, local, remote));
		assertEquals(json("{metadata:{keep:2},order:['a','c']}"), WorkspaceDataMerge.merge(base, local, remote, remote));
		assertEquals(json("{metadata:{keep:1,remove:1},order:['a']}"), base);
	}

	private JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
