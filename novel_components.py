import json
import re
from typing import Dict, Set, List
import networkx as nx


def parse_diff_file(diff_path: str) -> Set[int]:
    """
    Parse the diff file and extract all Java uniqueIds marked as Novel.
    
    Args:
        diff_path: Path to the diff text file
        
    Returns:
        Set of Java uniqueIds that are marked as Novel
    """
    novel_ids = set()
    
    with open(diff_path, 'r') as f:
        content = f.read()
    
    # Extract all Novel entries (format: "number: Novel")
    novel_pattern = r'(\d+):\s*Novel'
    matches = re.findall(novel_pattern, content)
    
    for match in matches:
        novel_ids.add(int(match))
    
    return novel_ids


def build_node_mappings(syntax_tree: dict) -> Dict[int, dict]:
    """
    Traverse the syntax tree once and build mapping from uniqueId to node info.
    
    Args:
        syntax_tree: The syntax tree structure (can be dict with filenames as keys or direct tree)
        
    Returns:
        Dictionary mapping uniqueId to node information
    """
    id_to_info = {}
    
    def traverse(node, current_file='Unknown'):
        if isinstance(node, dict):
            unique_id = node.get('uniqueId')
            
            # Build node info mapping if uniqueId exists
            if unique_id is not None:
                id_to_info[unique_id] = {
                    'content': node.get('content', node.get('open_content', '')),
                    'position': node.get('position', node.get('open_position', '')),
                    'nodeClass': node.get('nodeClass', 'Unknown'),
                    'node_kind': node.get('node_kind', 'unknown'),
                    'uniqueId': unique_id,
                    'file': current_file
                }
            
            # Recursively traverse children
            if 'children' in node:
                for child in node['children']:
                    traverse(child, current_file)
            
            # Traverse all other dict values
            for key, value in node.items():
                # Not sure!! but maybe we can skip this here?
                if key == 'children':
                    continue
                if isinstance(value, (dict, list)):
                    traverse(value, current_file)
        elif isinstance(node, list):
            for item in node:
                traverse(item, current_file)
    
    # Handle the case where syntax_tree is organized by filename
    for key, value in syntax_tree.items():
        if isinstance(value, dict) and ('nodeClass' in value or 'children' in value):
            traverse(value, key)
        else:
            traverse(syntax_tree, 'Unknown')
            break
    
    return id_to_info


def build_parent_mapping(syntax_tree: dict) -> Dict[int, int]:
    """
    Build a mapping from each node's uniqueId to its parent's uniqueId.
    
    Args:
        syntax_tree: The syntax tree structure
        
    Returns:
        Dictionary mapping child uniqueId to parent uniqueId
    """
    parent_map = {}
    
    def traverse(node, parent_unique_id=None):
        if isinstance(node, dict):
            unique_id = node.get('uniqueId')
            
            if unique_id is not None:
                if parent_unique_id is not None:
                    parent_map[unique_id] = parent_unique_id
                
                # Recursively traverse children
                if 'children' in node:
                    for child in node['children']:
                        traverse(child, unique_id)
            else:
                # Node without uniqueId, continue traversing with same parent
                if 'children' in node:
                    for child in node['children']:
                        traverse(child, parent_unique_id)
                        
            # Traverse all other dict values
            for key, value in node.items():
                # # Not sure!! but maybe skip children since it's already traversed above?
                # if key == 'children':
                #     continue
                # if isinstance(value, (dict, list)):
                if key != 'children' and isinstance(value, (dict, list)):
                    traverse(value, parent_unique_id)
        elif isinstance(node, list):
            for item in node:
                traverse(item, parent_unique_id)
    
    traverse(syntax_tree)
    return parent_map


def find_largest_novel_ancestor(unique_id: int, 
                                parent_map: Dict[int, int], 
                                novel_unique_ids: Set[int],
                                cache: Dict[int, int] = None) -> int:
    """
    Find the largest (highest in tree) novel ancestor of a node.
    
    Args:
        unique_id: The node's uniqueId to start from
        parent_map: Mapping from child uniqueId to parent uniqueId
        novel_unique_ids: Set of novel node uniqueIds
        cache: Optional cache for memoization
        
    Returns:
        The uniqueId of the largest novel ancestor, or the original unique_id if no novel ancestor exists
    """
    if cache is not None and unique_id in cache:
        return cache[unique_id]

    if unique_id not in novel_unique_ids:
        if cache is not None:
            cache[unique_id] = unique_id
        return unique_id
    
    largest_novel = unique_id
    current = unique_id
    
    # Traverse up the tree to find all novel ancestors
    while current in parent_map:
        parent_id = parent_map[current]
        if parent_id in novel_unique_ids:
            largest_novel = parent_id
        current = parent_id
    
    if cache is not None:
        cache[unique_id] = largest_novel
    return largest_novel


def filter_internal_edges(edges: List[dict], 
                          parent_map: Dict[int, int], 
                          novel_unique_ids: Set[int]) -> List[dict]:
    """
    Filter out edges where both endpoints are contained within the same novel ancestor node.
    
    Args:
        edges: List of edge dictionaries with fromUniqueId and toUniqueId
        parent_map: Mapping from child uniqueId to parent uniqueId
        novel_unique_ids: Set of novel node uniqueIds
        
    Returns:
        List of edges with internal edges removed
    """
    filtered_edges = []
    cache = {}
    for edge in edges:
        from_unique_id = edge['fromUniqueId']
        to_unique_id = edge['toUniqueId']
        
        # Find largest novel ancestor for both endpoints
        from_ancestor = find_largest_novel_ancestor(from_unique_id, parent_map, novel_unique_ids, cache)
        to_ancestor = find_largest_novel_ancestor(to_unique_id, parent_map, novel_unique_ids, cache)
        
        # Skip edges where both endpoints are contained within the same novel ancestor,
        # BUT keep edges where one endpoint IS the ancestor itself (structural parent edges
        # like FragmentToStmt that connect a child to its novel parent node).
        if from_ancestor == to_ancestor:
            if from_unique_id != from_ancestor and to_unique_id != to_ancestor:
                print(f"  Filtered internal edge: {from_unique_id} -> {to_unique_id} (both inside {from_ancestor})")
                continue
        
        filtered_edges.append(edge)
    
    return filtered_edges


def find_connected_components(edges: List[dict]) -> List[List[dict]]:
    """
    Find connected components and return them as lists of edges.
    
    Args:
        edges: List of edge dictionaries with 'fromUniqueId' and 'toUniqueId'
        
    Returns:
        List of components, where each component is a list of edge dictionaries
    """
    if not edges:
        return []
    
    # Build graph to find connected components
    G = nx.Graph()
    
    for edge in edges:
        from_unique_id = edge['fromUniqueId']
        to_unique_id = edge['toUniqueId']
        G.add_edge(from_unique_id, to_unique_id)
    
    # Find connected components as sets of nodes
    node_components = list(nx.connected_components(G))
    
    # Convert node components to edge components
    edge_components = []
    for node_component in node_components:
        component_edges = []
        
        # Find all edges within this component
        for edge in edges:
            from_unique_id = edge['fromUniqueId']
            to_unique_id = edge['toUniqueId']
            
            if from_unique_id in node_component and to_unique_id in node_component:
                component_edges.append(edge)
        
        if component_edges:
            edge_components.append(component_edges)
    
    # Sort components by number of edges (largest first)
    edge_components.sort(key=len, reverse=True)
    
    return edge_components


def faster_find_connected_components(edges: List[dict]) -> List[List[dict]]:
    if not edges:
        return []
    
    G = nx.Graph()
    
    for edge in edges:
        from_unique_id = edge['fromUniqueId']
        to_unique_id = edge['toUniqueId']
        G.add_edge(from_unique_id, to_unique_id)

    node_components = list(nx.connected_components(G))
    node_to_component = {}

    for idx, node_component in enumerate(node_components):
        for unique_id in node_component:
            node_to_component[unique_id] = idx

    edge_components = [[] for _ in range(len(node_components))]

    for edge in edges:
        from_unique_id = edge['fromUniqueId']
        to_unique_id = edge['toUniqueId']
        component_idx = node_to_component.get(from_unique_id)
        if component_idx is None:
            continue
        if component_idx != node_to_component.get(to_unique_id):
            raise ValueError(f"Edge endpoints in different components: {from_unique_id} -> {to_unique_id}")
        edge_components[component_idx].append(edge)

    edge_components = [component for component in edge_components if component]
    edge_components.sort(key=len, reverse=True)

    return edge_components


def filter_novel_edges(json_path: str, diff_path: str):
    """
    Filter novel edges from JSON using Java uniqueIds from result.txt.
    
    Args:
        json_path: Path to the JSON file (lhs.json or rhs.json)
        diff_path: Path to the diff text file (result.txt)
        
    Returns:
        Tuple of (components, unique_id_to_info)
    """
    with open(json_path, 'r') as f:
        data = json.load(f)
    
    # Parse novel Java uniqueIds from result.txt
    novel_unique_ids = parse_diff_file(diff_path)
    
    # Build node info mapping
    unique_id_to_info = build_node_mappings(data.get('syntax', {}))
    
    edges = data.get('nameResEdges', [])
    
    # Filter edges - working directly with uniqueIds
    filtered_edges = []
    seen_edges = set()  # For duplicate detection
    
    for edge in edges:
        from_unique_id = edge.get('fromId')
        to_unique_id = edge.get('toId')
        
        # Skip if uniqueIds are not available
        if from_unique_id is None or to_unique_id is None:
            continue
        
        # Check if both sides are novel (using Java uniqueIds)
        if from_unique_id in novel_unique_ids and to_unique_id in novel_unique_ids:
            # Create edge tuple for duplicate detection
            edge_tuple = (from_unique_id, to_unique_id, edge.get('from', ''), edge.get('to', ''))
            
            if edge_tuple not in seen_edges:
                seen_edges.add(edge_tuple)
                filtered_edges.append({
                    'from': edge.get('from'),
                    'to': edge.get('to'),
                    'fromUniqueId': from_unique_id,
                    'toUniqueId': to_unique_id
                })
        
    # Build parent mapping using uniqueIds
    parent_map = build_parent_mapping(data.get('syntax', {}))
    
    # Filter out internal edges (edges within same novel ancestor)
    external_edges = filter_internal_edges(filtered_edges, parent_map, novel_unique_ids)
    
    # Find connected components as lists of edges
    components = find_connected_components(external_edges)
    
    return components, unique_id_to_info


def components_to_json_structure(components: List[List[dict]], 
                                 unique_id_to_info: Dict[int, dict]) -> List[List[dict]]:
    """
    Convert components to JSON structure with edge details.
    
    Args:
        components: List of connected components (lists of edge dictionaries)
        unique_id_to_info: Mapping from uniqueId to node information
        
    Returns:
        List of components, where each component is a list of edge dictionaries with node details
    """
    json_components = []
    
    for component_edges in components:
        component_edge_list = []
        
        for edge in component_edges:
            from_unique_id = edge['fromUniqueId']
            to_unique_id = edge['toUniqueId']
            
            from_info = unique_id_to_info.get(from_unique_id, {})
            to_info = unique_id_to_info.get(to_unique_id, {})
            
            edge_dict = {
                'from': {
                    'java_id': from_unique_id,
                    'type': f"{from_info.get('nodeClass', 'Unknown')} ({from_info.get('node_kind', 'unknown')})",
                    'content': from_info.get('content', 'N/A'),
                    'position': from_info.get('position', 'N/A'),
                    'file': from_info.get('file', 'Unknown')
                },
                'to': {
                    'java_id': to_unique_id,
                    'type': f"{to_info.get('nodeClass', 'Unknown')} ({to_info.get('node_kind', 'unknown')})",
                    'content': to_info.get('content', 'N/A'),
                    'position': to_info.get('position', 'N/A'),
                    'file': to_info.get('file', 'Unknown')
                },
                'edge_label': edge.get('from', ''),
                'edge_label_full': f"{edge.get('from', '')} -> {edge.get('to', '')}"
            }
            
            component_edge_list.append(edge_dict)
        
        json_components.append(component_edge_list)
    
    return json_components


def create_diff_components(
    lhs_results: tuple,
    rhs_results: tuple,
    output_path: str
):
    """
    Create the final diff_components JSON with separate LHS (Removal) and RHS (Addition) changes.
    
    Args:
        lhs_results: Tuple of (components, unique_id_to_info) for LHS
        rhs_results: Tuple of (components, unique_id_to_info) for RHS
        output_path: Path for the output JSON file
    """
    lhs_components, lhs_unique_id_to_info = lhs_results
    rhs_components, rhs_unique_id_to_info = rhs_results
    
    # Convert LHS and RHS components to JSON
    lhs_components_json = components_to_json_structure(lhs_components, lhs_unique_id_to_info)
    rhs_components_json = components_to_json_structure(rhs_components, rhs_unique_id_to_info)
    
    # Create flat list of all components
    final_components = []
    
    # Add all LHS components as removals
    for lhs_component in lhs_components_json:
        component_changes = []
        for edge in lhs_component:
            edge_with_kind = edge.copy()
            edge_with_kind['kind'] = 'Removal'
            component_changes.append(edge_with_kind)
        
        if component_changes:
            final_components.append({'changes': component_changes})
    
    # Add all RHS components as additions
    for rhs_component in rhs_components_json:
        component_changes = []
        for edge in rhs_component:
            edge_with_kind = edge.copy()
            edge_with_kind['kind'] = 'Addition'
            component_changes.append(edge_with_kind)
        
        if component_changes:
            final_components.append({'changes': component_changes})
    
    output = {'diff_components': final_components}
    
    with open(output_path, 'w') as f:
        json.dump(output, f, indent=2)
    
    print(f"\nDiff components JSON written to {output_path}")
    print(f"  Total components: {len(final_components)}")
    print(f"  LHS components (removals): {len(lhs_components_json)}")
    print(f"  RHS components (additions): {len(rhs_components_json)}")


if __name__ == "__main__":
    import os
    base_path = os.path.join(os.path.dirname(__file__), "..", "difftastic", "Files")
    lhs_json_path = os.path.join(base_path, "lhs.json")
    rhs_json_path = os.path.join(base_path, "rhs.json")
    diff_path = os.path.join(base_path, "result.txt")
    diff_components_path = os.path.join(base_path, "diff_components.json")
    
    # Process LHS and RHS
    lhs_results = filter_novel_edges(lhs_json_path, diff_path)
    rhs_results = filter_novel_edges(rhs_json_path, diff_path)
    
    # Create diff_components.json with separate LHS and RHS components
    create_diff_components(lhs_results, rhs_results, diff_components_path)
