import java.util.function.BiConsumer;
import java.util.*;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Utility class for traversing JSON structures in preorder and postorder
 */
public class JsonTraverser {
    public static Map<String, Integer> getContentIdCache() {
        return contentIdCache;
    }

    public static Map<Integer, Integer> getListIdCache() {
        return listIdCache;
    }

    private static Map<String, Integer> contentIdCache = new HashMap<>();
    private static Map<Integer, Integer> listIdCache = new HashMap<>();

    /**
     * Traverse a JSON structure in preorder (parent first, then children)
     * 
     * @param json The JSON object/array to traverse
     * @param nodeProcessor Function to process each node (receives node and path)
     */
    public static void traversePreorder(Object json, BiConsumer<Object, String> nodeProcessor) {
        traversePreorder(json, "$", nodeProcessor);
    }
    
    private static void traversePreorder(Object json, String path, BiConsumer<Object, String> nodeProcessor) {
        // Process the current node first (preorder)
        nodeProcessor.accept(json, path);
        
        // Then process children based on the type
        if (json instanceof JSONObject) {
            JSONObject jsonObject = (JSONObject) json;
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = jsonObject.get(key);
                // Recursively process each child with updated path
                traversePreorder(value, path + "." + key, nodeProcessor);
            }
        } else if (json instanceof JSONArray) {
            JSONArray jsonArray = (JSONArray) json;
            for (int i = 0; i < jsonArray.length(); i++) {
                Object value = jsonArray.get(i);
                // Recursively process each array element with array index in path
                traversePreorder(value, path + "[" + i + "]", nodeProcessor);
            }
        }
        // For primitive values, we've already processed them
    }
    
    /**
     * Traverse a JSON structure in postorder (children first, then parent)
     * 
     * @param json The JSON object/array to traverse
     * @param nodeProcessor Function to process each node (receives node and path)
     */
    public static void traversePostorder(Object json, BiConsumer<Object, String> nodeProcessor) {
        traversePostorder(json, "$", nodeProcessor);
    }
    
    private static void traversePostorder(Object json, String path, BiConsumer<Object, String> nodeProcessor) {
        // Process children first (postorder)
        if (json instanceof JSONObject) {
            JSONObject jsonObject = (JSONObject) json;
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = jsonObject.get(key);
                // Recursively process each child with updated path
                traversePostorder(value, path + "." + key, nodeProcessor);
            }
        } else if (json instanceof JSONArray) {
            JSONArray jsonArray = (JSONArray) json;
            for (int i = 0; i < jsonArray.length(); i++) {
                Object value = jsonArray.get(i);
                // Recursively process each array element with array index in path
                traversePostorder(value, path + "[" + i + "]", nodeProcessor);
            }
        }
        
        // Process the current node last (postorder)
        nodeProcessor.accept(json, path);
    }

    /**
     * Updates content_id fields in a JSON object, ensuring consistent IDs for the same content.
     * This is particularly useful for syntax trees where the same content should have the same ID.
     * For atoms, the same content gets the same ID.
     * For lists, each list gets a unique ID regardless of content.
     * 
     * @param jsonObject The JSON object containing a "syntax" field to update
     */
    public static void updateContentId(JSONObject jsonObject) {
        if (!jsonObject.has("syntax")) {
            return; // Nothing to update
        }
        
        // Don't reset the cache if it exists already, neede for rhs
        
        // Start the nextId from the highest existing ID + 1
        int[] nextId = {1}; 
        for (int id : contentIdCache.values()) {
            if (id >= nextId[0]) {
                nextId[0] = id + 1;
            }
        }
        
        // Also check the list ID cache for the highest ID
        for (int id : listIdCache.values()) {
            if (id >= nextId[0]) {
                nextId[0] = id + 1;
            }
        }
        
        // Get the syntax array
        JSONArray syntaxArray = jsonObject.getJSONArray("syntax");
        
        // Process in postorder to ensure children are processed first
        traversePostorder(syntaxArray, (node, path) -> {
            if (node instanceof JSONObject) {
                JSONObject obj = (JSONObject) node;
                
                if (obj.has("node_kind")) {
                    String nodeKind = obj.getString("node_kind");
                    
                    if ("atom".equals(nodeKind) && obj.has("content")) {
                        // For atoms, use content-based IDs (same content = same ID)
                        String content = obj.getString("content");
                        int contentId;
                        
                        if (contentIdCache.containsKey(content)) {
                            // Reuse existing ID for this content
                            contentId = contentIdCache.get(content);
                        } else {
                            // Create new ID for this content
                            contentId = nextId[0]++;
                            contentIdCache.put(content, contentId);
                        }
                        
                        obj.put("content_id", contentId);
                    } 
                    else if ("list".equals(nodeKind)) {
                        // For lists, use a unique ID based on the list's ID
                        if (obj.has("id")) {
                            int listUniqueId = obj.getInt("id");
                            int contentId;
                            
                            if (listIdCache.containsKey(listUniqueId)) {
                                // Reuse existing ID for this list
                                contentId = listIdCache.get(listUniqueId);
                            } else {
                                // Create new ID for this list
                                contentId = nextId[0]++;
                                listIdCache.put(listUniqueId, contentId);
                            }
                            
                            obj.put("content_id", contentId);
                        }
                    }
                }
            }
        });
    }

    /**
     * Generates a structured syntax info based on the syntax tree
     * 
     * @param jsonObject The JSON object containing the syntax field
     * @return A JSONArray containing the detailed syntax info
     */
    public static JSONArray generateSyntaxInfo(JSONObject jsonObject) {
        if (!jsonObject.has("syntax")) {
            return new JSONArray(); // Empty if no syntax
        }
        
        // First, make sure all content_ids are updated
        updateContentId(jsonObject);
        
        // Get the syntax array
        JSONArray syntaxArray = jsonObject.getJSONArray("syntax");
        
        // Create maps to store each type of relationship
        Map<Integer, Integer> parentMap = new HashMap<>();  // node id -> parent id
        Map<Integer, Integer> prevSiblingMap = new HashMap<>();  // node id -> prev sibling id
        Map<Integer, Integer> nextSiblingMap = new HashMap<>();  // node id -> next sibling id  
        Map<Integer, Integer> prevNodeMap = new HashMap<>();  // node id -> prev node id
        Map<Integer, Integer> numAncestorsMap = new HashMap<>();  // node id -> depth
        Map<Integer, Integer> numAfterMap = new HashMap<>();  // node id -> nodes after
        Map<Integer, Boolean> isUniqueMap = new HashMap<>();  // node id -> is content unique
        
        // Create a map to track all nodes by ID
        Map<Integer, JSONObject> nodesById = new HashMap<>();
        
        // Create a list to track traversal order
        List<Integer> nodeOrder = new ArrayList<>();
        
        // Collect all nodes and build basic maps
        collectNodes(syntaxArray, nodesById, nodeOrder);
        
        // Update each relationship map
        updateParentRelationships(syntaxArray, parentMap);
        updateSiblingRelationships(syntaxArray, parentMap, prevSiblingMap, nextSiblingMap);
        updatePrevNodeRelationships(nodeOrder, prevNodeMap);
        updateNumAncestors(parentMap, numAncestorsMap);
        updateIsUnique(nodesById, isUniqueMap);
        updateNumAfter(parentMap, nodesById, numAfterMap);
        
        // Generate the syntaxInfo JSON array
        return createSyntaxInfoJson(
            nodesById, 
            parentMap, 
            prevSiblingMap, 
            nextSiblingMap, 
            prevNodeMap, 
            numAncestorsMap, 
            numAfterMap, 
            isUniqueMap
        );
    }

    /**
     * Updates the num_after values for each node, following the algorithm from the Rust code:
     * - For each node, num_after = parent_num_after + siblings.length - 1 - index
     */
    private static void updateNumAfter(
            Map<Integer, Integer> parentMap,
            Map<Integer, JSONObject> nodesById,
            Map<Integer, Integer> numAfterMap) {
        
        // Group nodes by parent
        Map<Integer, List<Integer>> childrenByParent = new HashMap<>();
        
        // Add all children to their parent's list
        for (Map.Entry<Integer, Integer> entry : parentMap.entrySet()) {
            int childId = entry.getKey();
            int parentId = entry.getValue();
            
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>())
                           .add(childId);
        }
        
        // Find root nodes (nodes without parents)
        Set<Integer> allNodeIds = new HashSet<>(nodesById.keySet());
        Set<Integer> rootIds = new HashSet<>(allNodeIds);
        rootIds.removeAll(parentMap.keySet());
        
        // Start with root nodes, with parent_num_after = 0
        for (Integer rootId : rootIds) {
            setNumAfter(rootId, 0, childrenByParent, numAfterMap);
        }
    }

    /**
     * Recursive helper to set num_after values
     * Translates the Rust function:
     * fn set_num_after(nodes: &[&Syntax], parent_num_after: usize)
     */
    private static void setNumAfter(
            int nodeId,
            int parentNumAfter,
            Map<Integer, List<Integer>> childrenByParent,
            Map<Integer, Integer> numAfterMap) {
        
        List<Integer> children = childrenByParent.get(nodeId);
        
        if (children != null) {
            for (int i = 0; i < children.size(); i++) {
                int childId = children.get(i);
                
                // Calculate num_after for this child:
                // parent_num_after + siblings.length - 1 - index
                int numAfter = parentNumAfter + children.size() - 1 - i;
                numAfterMap.put(childId, numAfter);
                
                // Recursively process this child's children
                setNumAfter(childId, numAfter, childrenByParent, numAfterMap);
            }
        }
        
        // Root nodes (not in any children list) get num_after = 0 by default
        // since they won't be processed in the loop above
        // No need to explicitly set this as the default value is 0
    }
    
    /**
     * Collects all nodes from the syntax tree and tracks their order
     */
    private static void collectNodes(
            JSONArray syntaxArray, 
            Map<Integer, JSONObject> nodesById, 
            List<Integer> nodeOrder) {
        
        traversePreorder(syntaxArray, (node, path) -> {
            if (node instanceof JSONObject) {
                JSONObject obj = (JSONObject) node;
                if (obj.has("id") && obj.has("node_kind")) {
                    int id = obj.getInt("id");
                    nodesById.put(id, obj);
                    nodeOrder.add(id);
                }
            }
        });
    }
    
    /**
     * Updates the parent relationships, accounting for nested arrays in the children field
     */
    private static void updateParentRelationships(
            JSONArray syntaxArray, 
            Map<Integer, Integer> parentMap) {
        
        traversePreorder(syntaxArray, (node, path) -> {
            if (node instanceof JSONObject) {
                JSONObject obj = (JSONObject) node;
                
                if (obj.has("node_kind") && obj.getString("node_kind").equals("list") && obj.has("children")) {
                    int parentId = obj.getInt("id");
                    Object children = obj.get("children");
                    
                    processChildrenForParent(children, parentId, parentMap);
                }
            }
        });
    }

    /**
     * Process children objects to establish parent relationships,
     * handling nested arrays that may be inside the children field
     */
    private static void processChildrenForParent(
            Object children,
            int parentId,
            Map<Integer, Integer> parentMap) {
        
        if (children instanceof JSONArray) {
            JSONArray childArray = (JSONArray) children;
            
            for (int i = 0; i < childArray.length(); i++) {
                Object child = childArray.get(i);
                
                if (child instanceof JSONObject) {
                    JSONObject childObj = (JSONObject) child;
                    if (childObj.has("id")) {
                        int childId = childObj.getInt("id");
                        parentMap.put(childId, parentId);
                    }
                } else if (child instanceof JSONArray) {
                    // If child is a JSONArray with a single JSONObject element,
                    // process it directly instead of as a nested array
                    JSONArray nestedArray = (JSONArray) child;
                    if (nestedArray.length() == 1 && nestedArray.get(0) instanceof JSONObject) {
                        JSONObject nestedObj = nestedArray.getJSONObject(0);
                        if (nestedObj.has("id")) {
                            int childId = nestedObj.getInt("id");
                            parentMap.put(childId, parentId);
                        }
                    } else {
                        // Handle nested arrays that don't fit the above pattern
                        processChildrenForParent(child, parentId, parentMap);
                    }
                }
            }
        }
    }
    
    /**
     * Updates previous and next sibling relationships
     */
    private static void updateSiblingRelationships(
            JSONArray syntaxArray, 
            Map<Integer, Integer> parentMap, 
            Map<Integer, Integer> prevSiblingMap, 
            Map<Integer, Integer> nextSiblingMap) {
        
        // Group nodes by parent
        Map<Integer, List<Integer>> childrenByParent = new HashMap<>();
        
        for (Map.Entry<Integer, Integer> entry : parentMap.entrySet()) {
            int childId = entry.getKey();
            int parentId = entry.getValue();
            
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>())
                           .add(childId);
        }
        
        // For each parent, set sibling relationships
        for (List<Integer> siblings : childrenByParent.values()) {
            for (int i = 0; i < siblings.size(); i++) {
                int currentId = siblings.get(i);
                
                if (i > 0) {
                    // Has previous sibling
                    prevSiblingMap.put(currentId, siblings.get(i - 1));
                }
                
                if (i < siblings.size() - 1) {
                    // Has next sibling
                    nextSiblingMap.put(currentId, siblings.get(i + 1));
                }
            }
        }
    }
    
    /**
     * Updates previous node relationships based on traversal order
     */
    private static void updatePrevNodeRelationships(
            List<Integer> nodeOrder, 
            Map<Integer, Integer> prevNodeMap) {
        
        for (int i = 1; i < nodeOrder.size(); i++) {
            prevNodeMap.put(nodeOrder.get(i), nodeOrder.get(i - 1));
        }
    }
    
    /**
     * Updates the number of ancestors (depth) for each node
     */
    private static void updateNumAncestors(
            Map<Integer, Integer> parentMap, 
            Map<Integer, Integer> numAncestorsMap) {
        
        for (Integer nodeId : parentMap.keySet()) {
            int depth = 0;
            Integer currentId = nodeId;
            
            while (parentMap.containsKey(currentId)) {
                depth++;
                currentId = parentMap.get(currentId);
            }
            
            numAncestorsMap.put(nodeId, depth);
        }
        
        // Root nodes (those without parents) have depth 0
        // They will not be in numAncestorsMap, which is fine
    }
    
    /**
     * Determines if each node's content is unique
     */
    private static void updateIsUnique(
            Map<Integer, JSONObject> nodesById, 
            Map<Integer, Boolean> isUniqueMap) {
        
        // First, count occurrences of each content_id
        Map<Integer, Integer> contentIdCount = new HashMap<>();
        
        for (JSONObject node : nodesById.values()) {
            if (node.has("content_id")) {
                int contentId = node.getInt("content_id");
                contentIdCount.put(contentId, contentIdCount.getOrDefault(contentId, 0) + 1);
            }
        }
        
        // Set uniqueness for each node
        for (Map.Entry<Integer, JSONObject> entry : nodesById.entrySet()) {
            int nodeId = entry.getKey();
            JSONObject node = entry.getValue();
            
            if (node.has("content_id")) {
                int contentId = node.getInt("content_id");
                isUniqueMap.put(nodeId, contentIdCount.get(contentId) == 1);
            }
        }
    }

    /**
     * Creates the final syntaxInfo JSON array from the relationship maps
     * Uses a flat structure with specific field names as required
     * IDs are stored as numbers, and null values are stored as "None" strings
     */
    private static JSONArray createSyntaxInfoJson(
            Map<Integer, JSONObject> nodesById,
            Map<Integer, Integer> parentMap,
            Map<Integer, Integer> prevSiblingMap,
            Map<Integer, Integer> nextSiblingMap,
            Map<Integer, Integer> prevNodeMap,
            Map<Integer, Integer> numAncestorsMap,
            Map<Integer, Integer> numAfterMap,
            Map<Integer, Boolean> isUniqueMap) {
    
        JSONArray syntaxInfo = new JSONArray();
        
        for (Map.Entry<Integer, JSONObject> entry : nodesById.entrySet()) {
            int nodeId = entry.getKey();
            JSONObject node = entry.getValue();
            
            JSONObject infoNode = new JSONObject();
            
            // Add all fields except num_descendants
            infoNode.put("id", nodeId);
            
            if (node.has("content_id")) {
                infoNode.put("content_id", node.getInt("content_id"));
            } else {
                infoNode.put("content_id", 0);  // Default value
            }
            
            Integer parentId = parentMap.get(nodeId);
            infoNode.put("parent", parentId != null ? parentId : "None");
            
            Integer prevSiblingId = prevSiblingMap.get(nodeId);
            infoNode.put("prev_sibling", prevSiblingId != null ? prevSiblingId : "None");
            
            Integer nextSiblingId = nextSiblingMap.get(nodeId);
            infoNode.put("next_sibling", nextSiblingId != null ? nextSiblingId : "None");
            
            Integer prevNodeId = prevNodeMap.get(nodeId);
            infoNode.put("prev_node", prevNodeId != null ? prevNodeId : "None");
            
            int numAncestors = numAncestorsMap.getOrDefault(nodeId, 0);
            infoNode.put("num_ancestors", numAncestors);
            
            int numAfter = numAfterMap.getOrDefault(nodeId, 0);
            infoNode.put("num_after", numAfter);
                    
            boolean unique = isUniqueMap.getOrDefault(nodeId, true);
            infoNode.put("is_unique", unique);
            
            syntaxInfo.put(infoNode);
        }
        
        return syntaxInfo;
    }

    /**
     * Updates the num_descendants field for all list nodes in the syntax tree.
     * Similar to updateContentId, this method ensures each list node correctly
     * reflects the number of descendants in its subtree.
     * 
     * @param jsonObject The JSON object containing a "syntax" field to update
     */
    public static void updateNumDescendants(JSONObject jsonObject) {
        if (!jsonObject.has("syntax")) {
            return; // Nothing to update
        }
        
        // Get the syntax array
        JSONArray syntaxArray = jsonObject.getJSONArray("syntax");
        
        // Build a map of parent relationships for faster lookup
        Map<Integer, Integer> parentMap = new HashMap<>();
        traversePreorder(syntaxArray, (node, path) -> {
            if (node instanceof JSONObject) {
                JSONObject obj = (JSONObject) node;
                
                if (obj.has("node_kind") && obj.getString("node_kind").equals("list") && obj.has("children")) {
                    int parentId = obj.getInt("id");
                    Object children = obj.get("children");
                    
                    buildParentMap(children, parentId, parentMap);
                }
            }
        });
        
        // Create a map to store all JSON objects by their ID for direct access
        Map<Integer, JSONObject> nodesById = new HashMap<>();
        traversePreorder(syntaxArray, (node, path) -> {
            if (node instanceof JSONObject) {
                JSONObject obj = (JSONObject) node;
                if (obj.has("id")) {
                    nodesById.put(obj.getInt("id"), obj);
                }
            }
        });
        
        // Group nodes by parent for easier child lookup
        Map<Integer, List<Integer>> childrenByParent = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : parentMap.entrySet()) {
            int childId = entry.getKey();
            int parentId = entry.getValue();
            
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(childId);
        }
        
        // Process in post-order to ensure children are processed before parents
        traversePostorder(syntaxArray, (node, path) -> {
            if (node instanceof JSONObject) {
                JSONObject obj = (JSONObject) node;
                
                if (obj.has("id") && obj.has("node_kind") && obj.getString("node_kind").equals("list")) {
                    int nodeId = obj.getInt("id");
                    int directChildrenCount = childrenByParent.getOrDefault(nodeId, Collections.emptyList()).size();
                    int descendantsOfChildren = 0;
                    
                    for (Integer childId : childrenByParent.getOrDefault(nodeId, Collections.emptyList())) {
                        JSONObject childObj = nodesById.get(childId);
                        
                        if (childObj.has("node_kind") && childObj.getString("node_kind").equals("list") && 
                                childObj.has("num_descendants")) {
                            // Add this child's descendants
                            descendantsOfChildren += childObj.getInt("num_descendants");
                        }
                    }
                    
                    // Total descendants = direct children + descendants of children
                    int totalDescendants = directChildrenCount + descendantsOfChildren;
                    obj.put("num_descendants", totalDescendants);
                }
            }
        });
    }

    /**
     * Builds the parent map for descendants calculation
     */
    private static void buildParentMap(Object children, int parentId, Map<Integer, Integer> parentMap) {
        if (children instanceof JSONArray) {
            JSONArray childArray = (JSONArray) children;
            
            for (int i = 0; i < childArray.length(); i++) {
                Object child = childArray.get(i);
                
                if (child instanceof JSONObject) {
                    JSONObject childObj = (JSONObject) child;
                    if (childObj.has("id")) {
                        int childId = childObj.getInt("id");
                        parentMap.put(childId, parentId);
                    }
                } else if (child instanceof JSONArray) {
                    // Handle nested arrays inside the children field
                    buildParentMap(child, parentId, parentMap);
                }
            }
        }
    }

    public static void setListIdCache(Map<Integer, Integer> externalListIdCache) {
        listIdCache = new HashMap<>(externalListIdCache);
    }
}