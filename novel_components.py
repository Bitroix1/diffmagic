import json
import re
from typing import Dict, Set, List
import networkx as nx


def parse_diff_file(diff_path: str) -> Set[int]:
    """
    Parse the diff file and extract all IDs marked as Novel.
    
    Args:
        diff_path: Path to the diff text file
        
    Returns:
        Set of IDs that are marked as Novel
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


def build_node_mappings(syntax_tree: dict) -> tuple:
    """
    Traverse the syntax tree once and build all necessary mappings.
    
    Args:
        syntax_tree: The syntax tree structure (can be dict with filenames as keys or direct tree)
        
    Returns:
        Tuple of (unique_id_to_id, id_to_unique_id, id_to_info) mappings
    """
    unique_id_to_id = {}
    id_to_unique_id = {}
    id_to_info = {}
    
    def traverse(node, current_file='Unknown'):
        if isinstance(node, dict):
            node_id = node.get('id')
            unique_id = node.get('uniqueId')
            
            # Build all mappings if both id and uniqueId exist
            if node_id is not None and unique_id is not None:
                unique_id_to_id[unique_id] = node_id
                id_to_unique_id[node_id] = unique_id
            
            # Build node info mapping if id exists
            if node_id is not None:
                id_to_info[node_id] = {
                    'content': node.get('content', node.get('open_content', '')),
                    'position': node.get('position', node.get('open_position', '')),
                    'nodeClass': node.get('nodeClass', 'Unknown'),
                    'node_kind': node.get('node_kind', 'unknown'),
                    'uniqueId': unique_id if unique_id is not None else -1,
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
    # Check if the top-level keys look like filenames
    for key, value in syntax_tree.items():
        if isinstance(value, dict) and ('nodeClass' in value or 'children' in value):
            # This key is likely a filename
            traverse(value, key)
        else:
            # Not organized by filename, just traverse
            traverse(syntax_tree, 'Unknown')
            break
    
    return unique_id_to_id, id_to_unique_id, id_to_info


def build_parent_mapping(syntax_tree: dict) -> Dict[int, int]:
    """
    Build a mapping from each node's id to its parent's id.
    
    Args:
        syntax_tree: The syntax tree structure
        
    Returns:
        Dictionary mapping child id to parent id
    """
    parent_map = {}
    
    def traverse(node, parent_id=None):
        if isinstance(node, dict):
            if 'id' in node:
                node_id = node['id']
                if parent_id is not None:
                    parent_map[node_id] = parent_id
                
                # Recursively traverse children
                if 'children' in node:
                    for child in node['children']:
                        traverse(child, node_id)
            else:
                # Node without id, continue traversing
                if 'children' in node:
                    for child in node['children']:
                        traverse(child, parent_id)
                        
            # Traverse all other dict values
            for key, value in node.items():
                # Not sure!! but maybe skip children since it's already traversed above?
                if key == 'children':
                    continue
                if isinstance(value, (dict, list)):
                    traverse(value, parent_id)
        elif isinstance(node, list):
            for item in node:
                traverse(item, parent_id)
    
    traverse(syntax_tree)
    return parent_map


def find_largest_novel_ancestor(node_id: int, 
                                parent_map: Dict[int, int], 
                                novel_ids: Set[int],
                                id_to_unique_id: Dict[int, int],
                                cache: Dict[int, int] = None) -> int:
    """
    Find the largest (highest in tree) novel ancestor of a node that has a valid uniqueId.
    
    Args:
        node_id: The node to start from
        parent_map: Mapping from child id to parent id
        novel_ids: Set of novel node ids
        id_to_unique_id: Mapping from id to uniqueId
        
    Returns:
        The id of the largest novel ancestor with a valid uniqueId, or the original node_id if no novel ancestor exists
    """
    if cache is not None and node_id in cache:
        return cache[node_id]

    if node_id not in novel_ids:
        if cache is not None:
            cache[node_id] = node_id
        return node_id
    
    # Only consider this node as a candidate if it has a valid uniqueId
    largest_novel = node_id if id_to_unique_id.get(node_id, -1) != -1 else None
    current = node_id
    
    # Traverse up the tree to find all novel ancestors with valid uniqueIds
    while current in parent_map:
        parent_id = parent_map[current]
        if parent_id in novel_ids:
            # Only update largest_novel if this parent has a valid uniqueId
            parent_unique_id = id_to_unique_id.get(parent_id, -1)
            if parent_unique_id != -1:
                largest_novel = parent_id
            current = parent_id
        else:
            break
    
    # If we didn't find any valid ancestor (including the node itself), return the original node
    res = largest_novel if largest_novel is not None else node_id
    if cache is not None:
        cache[node_id] = res
    return res


def filter_internal_edges(edges: List[dict], 
                          parent_map: Dict[int, int], 
                          novel_ids: Set[int],
                          id_to_unique_id: Dict[int, int]) -> List[dict]:
    """
    Filter out edges where both endpoints are contained within the same novel ancestor node.
    
    Args:
        edges: List of edge dictionaries
        parent_map: Mapping from child id to parent id
        novel_ids: Set of novel node ids
        id_to_unique_id: Mapping from id to uniqueId
        
    Returns:
        List of edges with internal edges removed
    """
    filtered_edges = []
    cache = {}
    for edge in edges:
        from_id = edge['fromId']
        to_id = edge['toId']
        
        # Find largest novel ancestor for both endpoints
        from_ancestor = find_largest_novel_ancestor(from_id, parent_map, novel_ids, id_to_unique_id, cache)
        to_ancestor = find_largest_novel_ancestor(to_id, parent_map, novel_ids, id_to_unique_id, cache)
        
        # Skip edges where both endpoints have the same novel ancestor (internal edges)
        if from_ancestor == to_ancestor:
            print(f"  Filtered internal edge: {from_id} -> {to_id} (both inside {from_ancestor})")
            continue
        
        filtered_edges.append(edge)
    
    return filtered_edges


def find_connected_components(edges: List[dict]) -> List[List[dict]]:
    """
    Find connected components and return them as lists of edges.
    
    Args:
        edges: List of edge dictionaries with 'fromId' and 'toId'
        
    Returns:
        List of components, where each component is a list of edge dictionaries
    """
    if not edges:
        return []
    
    # Build graph to find connected components
    G = nx.Graph()
    
    for edge in edges:
        from_id = edge['fromId']
        to_id = edge['toId']
        G.add_edge(from_id, to_id)
    
    # Find connected components as sets of nodes
    node_components = list(nx.connected_components(G))
    
    # Convert node components to edge components
    edge_components = []
    for node_component in node_components:
        component_edges = []
        
        # Find all edges within this component
        for edge in edges:
            from_id = edge['fromId']
            to_id = edge['toId']
            
            if from_id in node_component and to_id in node_component:
                component_edges.append(edge)
        
        if component_edges:
            edge_components.append(component_edges)
    
    # Sort components by number of edges (largest first)
    edge_components.sort(key=len, reverse=True)
    
    return edge_components


def faster_find_connected_components(edges: List[dict]) -> List[List[dict]]:
    # same as `find_connected_components`
    if not edges:
        return []
    # same as `find_connected_components`
    G = nx.Graph()
    # same as `find_connected_components`
    for edge in edges:
        from_id = edge['fromId']
        to_id = edge['toId']
        G.add_edge(from_id, to_id)

    # list of sets of node ids that are in the same CC.
    node_components = list(nx.connected_components(G))

    # make an empty dict first
    node_to_component = {}

    # this is going over all node components and mapping each node to its idx
    for idx, node_component in enumerate(node_components):
        for node_id in node_component:
            node_to_component[node_id] = idx

    # make an empty list of edge components
    edge_components = [[] for _ in range(len(node_components))]

    # fill the edge components
    for edge in edges:
        from_id = edge['fromId']
        to_id = edge['toId']
        component_idx = node_to_component.get(from_id)
        if component_idx is None:
            continue
        # just a sanity check
        if component_idx != node_to_component.get(to_id):
            raise ValueError(f"Edge endpoints in different components: {from_id} -> {to_id}")
        edge_components[component_idx].append(edge)

    # we probably want to only keep non-empty components
    edge_components = [component for component in edge_components if component]

    # same as `find_connected_components`
    edge_components.sort(key=len, reverse=True)

    return edge_components

def filter_novel_edges(json_path: str, diff_path: str):
    """
    MaBuild all node mappings in one pass (this now includes file information per node)with edges and syntax tree
        diff_path: Path to the diff text file
    """
    with open(json_path, 'r') as f:
        data = json.load(f)
    
    novel_ids = parse_diff_file(diff_path)
    
    # Build all node mappings in one pass
    unique_id_to_id, id_to_unique_id, id_to_info = build_node_mappings(data.get('syntax', {}))
    
    edges = data.get('nameResEdges', [])
    
    # Filter edges and translate IDs
    filtered_edges = []
    seen_edges = set()  # For duplicate detection
    
    for edge in edges:
        from_unique_id = edge.get('fromId')
        to_unique_id = edge.get('toId')
        
        # Translate unique IDs to incremental IDs
        from_id = unique_id_to_id.get(from_unique_id)
        to_id = unique_id_to_id.get(to_unique_id)
        
        # Skip if we can't find the mapping
        if from_id is None or to_id is None:
            continue
        
        # Check if both sides are novel
        if from_id in novel_ids and to_id in novel_ids:
            # Create edge tuple for duplicate detection
            edge_tuple = (from_id, to_id, edge.get('from', ''), edge.get('to', ''))
            
            if edge_tuple not in seen_edges:
                seen_edges.add(edge_tuple)
                filtered_edges.append({
                    'from': edge.get('from'),
                    'to': edge.get('to'),
                    'fromId': from_id,
                    'toId': to_id,
                    'fromUniqueId': from_unique_id,
                    'toUniqueId': to_unique_id
                })
        
    # Build parent mapping for filtering internal edges
    parent_map = build_parent_mapping(data.get('syntax', {}))
    
    # Filter out internal edges (edges within same novel ancestor)
    external_edges = filter_internal_edges(filtered_edges, parent_map, novel_ids, id_to_unique_id)
    
    # Find connected components as lists of edges
    components = find_connected_components(external_edges)
    
    return components, id_to_info


def components_to_json_structure(components: List[List[dict]], 
                                 id_to_info: Dict[int, dict]) -> List[List[dict]]:
    """
    Convert components to JSON structure with edge details.
    
    Args:
        components: List of connected components (lists of edge dictionaries)
        id_to_info: Mapping from node ID to node information (includes file info)
        
    Returns:
        List of components, where each component is a list of edge dictionaries with node details
    """
    json_components = []
    
    for component_edges in components:
        component_edge_list = []
        
        for edge in component_edges:
            from_id = edge['fromId']
            to_id = edge['toId']
            
            from_info = id_to_info.get(from_id, {})
            to_info = id_to_info.get(to_id, {})
            
            edge_dict = {
                'from': {
                    'rust_id': from_id,
                    'java_id': from_info.get('uniqueId', -1),
                    'type': f"{from_info.get('nodeClass', 'Unknown')} ({from_info.get('node_kind', 'unknown')})",
                    'content': from_info.get('content', 'N/A'),
                    'position': from_info.get('position', 'N/A'),
                    'file': from_info.get('file', 'Unknown')
                },
                'to': {
                    'rust_id': to_id,
                    'java_id': to_info.get('uniqueId', -1),
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
        lhs_results: Tuple of (components, id_to_info) for LHS
        rhs_results: Tuple of (components, id_to_info) for RHS
        output_path: Path for the output JSON file
    """
    lhs_components, lhs_id_to_info = lhs_results
    rhs_components, rhs_id_to_info = rhs_results
    
    # Convert LHS and RHS components to JSON
    lhs_components_json = components_to_json_structure(lhs_components, lhs_id_to_info)
    rhs_components_json = components_to_json_structure(rhs_components, rhs_id_to_info)
    
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
