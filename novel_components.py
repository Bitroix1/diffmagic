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


def parse_change_map(diff_path: str) -> Dict[int, int]:
    """
    Parse the ChangeMap from the diff file to find equivalent nodes between LHS and RHS.
    
    Args:
        diff_path: Path to the diff text file
        
    Returns:
        Dictionary mapping LHS node ID to RHS node ID for unchanged nodes
    """
    change_map = {}
    
    with open(diff_path, 'r') as f:
        content = f.read()
    
    # Extract ChangeMap section
    changemap_pattern = r'Changes: ChangeMap \{(.*?)\}'
    match = re.search(changemap_pattern, content, re.DOTALL)
    
    if not match:
        return change_map
    
    changemap_content = match.group(1)
    
    # Extract Unchanged mappings (format: "lhs_id: Unchanged(ID: rhs_id)")
    unchanged_pattern = r'(\d+):\s*Unchanged\(ID:\s*(\d+)\)'
    matches = re.findall(unchanged_pattern, changemap_content)
    
    for lhs_id, rhs_id in matches:
        change_map[int(lhs_id)] = int(rhs_id)
    
    return change_map


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
            for value in node.values():
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
                if key != 'children' and isinstance(value, (dict, list)):
                    traverse(value, parent_id)
        elif isinstance(node, list):
            for item in node:
                traverse(item, parent_id)
    
    traverse(syntax_tree)
    return parent_map


def find_largest_novel_ancestor(node_id: int, 
                                parent_map: Dict[int, int], 
                                novel_ids: Set[int],
                                id_to_unique_id: Dict[int, int]) -> int:
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
    if node_id not in novel_ids:
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
    return largest_novel if largest_novel is not None else node_id


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
        
    for edge in edges:
        from_id = edge['fromId']
        to_id = edge['toId']
        
        # Find largest novel ancestor for both endpoints
        from_ancestor = find_largest_novel_ancestor(from_id, parent_map, novel_ids, id_to_unique_id)
        to_ancestor = find_largest_novel_ancestor(to_id, parent_map, novel_ids, id_to_unique_id)
        
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
    
    # Map to store edges by their endpoints
    edge_map = {}
    for edge in edges:
        from_id = edge['fromId']
        to_id = edge['toId']
        G.add_edge(from_id, to_id)
        
        # Store edge in map (both directions since graph is undirected)
        if (from_id, to_id) not in edge_map:
            edge_map[(from_id, to_id)] = edge
        if (to_id, from_id) not in edge_map:
            edge_map[(to_id, from_id)] = edge
    
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


def filter_novel_edges(json_path: str, diff_path: str):
    """
    MaBuild all node mappings in one pass (this now includes file information per node)with edges and syntax tree
        diff_path: Path to the diff text file
    """
    with open(json_path, 'r') as f:
        data = json.load(f)
    
    novel_ids = parse_diff_file(diff_path)
    
    # Extract filename from the syntax tree
    filename = data.get('syntax', {}).get('filename', 'Unknown')
    
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


def combine_and_save_results(lhs_results: tuple, rhs_results: tuple, output_path: str):
    """
    Combine LHS and RHS results and save to files.
    
    Args:
        lhs_results: Tuple of (components, id_to_info) for LHS
        rhs_results: Tuple of (components, id_to_info) for RHS
        output_path: Base path for output files
    """
    lhs_components, lhs_id_to_info = lhs_results
    rhs_components, rhs_id_to_info = rhs_results
    
    # Create components JSON structure
    lhs_components_json = components_to_json_structure(lhs_components, lhs_id_to_info)
    rhs_components_json = components_to_json_structure(rhs_components, rhs_id_to_info)
    
    components_json_output = {
        'lhs': lhs_components_json,
        'rhs': rhs_components_json
    }
    
    # Save components JSON
    with open(output_path, 'w') as f:
        json.dump(components_json_output, f, indent=2)
    
    print(f"\nComponents JSON written to {output_path}")
    print(f"  LHS components: {len(lhs_components)}")
    print(f"  LHS total edges: {sum(len(comp) for comp in lhs_components)}")
    print(f"  RHS components: {len(rhs_components)}")
    print(f"  RHS total edges: {sum(len(comp) for comp in rhs_components)}")


def get_edge_parents(edge: dict, parent_map: Dict[int, int]) -> tuple:
    """
    Get the parent IDs for both endpoints of an edge.
    
    Args:
        edge: Edge dictionary with fromId and toId
        parent_map: Mapping from child id to parent id
        
    Returns:
        Tuple of (from_parent_id, to_parent_id), or (None, None) if no parents
    """
    from_id = edge['fromId']
    to_id = edge['toId']
    
    from_parent = parent_map.get(from_id)
    to_parent = parent_map.get(to_id)
    
    return from_parent, to_parent


def get_component_novel_nodes(component: List[dict]) -> Set[int]:
    """
    Extract all unique node IDs from a component's edges.
    
    Args:
        component: List of edge dictionaries with fromId and toId
        
    Returns:
        Set of all node IDs involved in the component
    """
    nodes = set()
    for edge in component:
        nodes.add(edge['fromId'])
        nodes.add(edge['toId'])
    return nodes


def merge_components_by_parent_equivalence(
    lhs_components: List[List[dict]],
    rhs_components: List[List[dict]],
    lhs_parent_map: Dict[int, int],
    rhs_parent_map: Dict[int, int],
    change_map: Dict[int, int],
    lhs_id_to_info: Dict[int, dict],
    rhs_id_to_info: Dict[int, dict]
) -> List[dict]:
    """
    Merge LHS and RHS components based on equivalent parent nodes and child offsets.
    Components are merged if nodes share equivalent parents (via change_map) AND
    have matching child offsets (child_id - parent_id), which represents the child's index.
    
    Args:
        lhs_components: List of LHS components (lists of edges)
        rhs_components: List of RHS components (lists of edges)
        lhs_parent_map: Parent mapping for LHS nodes
        rhs_parent_map: Parent mapping for RHS nodes
        change_map: Mapping from LHS node IDs to RHS node IDs
        lhs_id_to_info: Node info mapping for LHS
        rhs_id_to_info: Node info mapping for RHS
        
    Returns:
        List of merged components with kind annotations
    """
    merged_components = []
    
    # Track which RHS components have been merged
    used_rhs_components = set()
    
    # For each LHS component, try to find matching RHS components
    for lhs_comp_idx, lhs_comp in enumerate(lhs_components):
        # Get all novel nodes in this LHS component with their parent-child relationships
        lhs_nodes = get_component_novel_nodes(lhs_comp)
        lhs_parent_offset_pairs = set()  # (parent_id, offset)
        
        for node_id in lhs_nodes:
            parent_id = lhs_parent_map.get(node_id)
            if parent_id is not None:
                offset = node_id - parent_id
                lhs_parent_offset_pairs.add((parent_id, offset))
        
        # Find RHS components with nodes that have equivalent parents AND matching offsets
        matching_rhs_components = []
        
        for rhs_comp_idx, rhs_comp in enumerate(rhs_components):
            if rhs_comp_idx in used_rhs_components:
                continue
            
            # Get all novel nodes in this RHS component with their parent-child relationships
            rhs_nodes = get_component_novel_nodes(rhs_comp)
            rhs_parent_offset_pairs = set()  # (parent_id, offset)
            
            for node_id in rhs_nodes:
                parent_id = rhs_parent_map.get(node_id)
                if parent_id is not None:
                    offset = node_id - parent_id
                    rhs_parent_offset_pairs.add((parent_id, offset))
            
            # Check if any (parent, offset) pair from LHS matches any from RHS
            # Parents must be equivalent via change_map, offsets must match exactly
            has_match = False
            for lhs_parent, lhs_offset in lhs_parent_offset_pairs:
                for rhs_parent, rhs_offset in rhs_parent_offset_pairs:
                    # Check if parents are equivalent AND offsets match
                    if change_map.get(lhs_parent) == rhs_parent and lhs_offset == rhs_offset:
                        has_match = True
                        print(f"  Merging components: LHS parent {lhs_parent} matches RHS parent {rhs_parent} with offset {lhs_offset}")
                        break
                
                if has_match:
                    break
            
            if has_match:
                matching_rhs_components.append(rhs_comp_idx)
                used_rhs_components.add(rhs_comp_idx)
        
        # Create merged component
        merged_component = {
            'removals': components_to_json_structure([lhs_comp], lhs_id_to_info)[0] if lhs_comp else [],
            'additions': []
        }
        
        # Add matching RHS components as additions
        for rhs_comp_idx in matching_rhs_components:
            rhs_comp = rhs_components[rhs_comp_idx]
            rhs_comp_json = components_to_json_structure([rhs_comp], rhs_id_to_info)[0]
            merged_component['additions'].extend(rhs_comp_json)
        
        merged_components.append(merged_component)
    
    # Add remaining RHS components that weren't matched (pure additions)
    for rhs_comp_idx, rhs_comp in enumerate(rhs_components):
        if rhs_comp_idx not in used_rhs_components:
            merged_component = {
                'removals': [],
                'additions': components_to_json_structure([rhs_comp], rhs_id_to_info)[0]
            }
            merged_components.append(merged_component)
    
    return merged_components


def create_diff_components(
    lhs_results: tuple,
    rhs_results: tuple,
    lhs_parent_map: Dict[int, int],
    rhs_parent_map: Dict[int, int],
    change_map: Dict[int, int],
    output_path: str
):
    """
    Create the final diff_components JSON with merged LHS/RHS changes.
    
    Args:
        lhs_results: Tuple of (components, id_to_info) for LHS
        rhs_results: Tuple of (components, id_to_info) for RHS
        lhs_parent_map: Parent mapping for LHS nodes
        rhs_parent_map: Parent mapping for RHS nodes
        change_map: Mapping from LHS node IDs to RHS node IDs
        output_path: Path for the output JSON file
    """
    lhs_components, lhs_id_to_info = lhs_results
    rhs_components, rhs_id_to_info = rhs_results
    
    merged_components = merge_components_by_parent_equivalence(
        lhs_components,
        rhs_components,
        lhs_parent_map,
        rhs_parent_map,
        change_map,
        lhs_id_to_info,
        rhs_id_to_info
    )
    
    # Add kind field to each edge
    final_components = []
    for component in merged_components:
        final_component = {
            'changes': []
        }
        
        # Add removals with kind="Removal"
        for edge in component['removals']:
            edge_with_kind = edge.copy()
            edge_with_kind['kind'] = 'Removal'
            final_component['changes'].append(edge_with_kind)
        
        # Add additions with kind="Addition"
        for edge in component['additions']:
            edge_with_kind = edge.copy()
            edge_with_kind['kind'] = 'Addition'
            final_component['changes'].append(edge_with_kind)
        
        if final_component['changes']:
            final_components.append(final_component)
    
    output = {'diff_components': final_components}
    
    with open(output_path, 'w') as f:
        json.dump(output, f, indent=2)
    
    print(f"\nDiff components JSON written to {output_path}")
    print(f"  Total merged components: {len(final_components)}")
    print(f"  Components with both removals and additions: {sum(1 for c in merged_components if c['removals'] and c['additions'])}")
    print(f"  Components with only removals: {sum(1 for c in merged_components if c['removals'] and not c['additions'])}")
    print(f"  Components with only additions: {sum(1 for c in merged_components if not c['removals'] and c['additions'])}")


if __name__ == "__main__":
    import os
    base_path = os.path.join(os.path.dirname(__file__), "..", "difftastic", "Files")
    lhs_json_path = os.path.join(base_path, "lhs.json")
    rhs_json_path = os.path.join(base_path, "rhs.json")
    diff_path = os.path.join(base_path, "result.txt")
    output_path = os.path.join(base_path, "output.json")
    diff_components_path = os.path.join(base_path, "diff_components.json")
    
    # Parse change map from diff file
    change_map = parse_change_map(diff_path)
    print(f"Parsed ChangeMap with {len(change_map)} mappings")
    
    # Process LHS and RHS
    lhs_results = filter_novel_edges(lhs_json_path, diff_path)
    rhs_results = filter_novel_edges(rhs_json_path, diff_path)
    
    # Save original output.json (for backwards compatibility)
    combine_and_save_results(lhs_results, rhs_results, output_path)
    
    # Load syntax trees to build parent maps
    with open(lhs_json_path, 'r') as f:
        lhs_data = json.load(f)
    with open(rhs_json_path, 'r') as f:
        rhs_data = json.load(f)
    
    lhs_parent_map = build_parent_mapping(lhs_data.get('syntax', {}))
    rhs_parent_map = build_parent_mapping(rhs_data.get('syntax', {}))
    
    # Create diff_components.json with merged changes
    create_diff_components(
        lhs_results,
        rhs_results,
        lhs_parent_map,
        rhs_parent_map,
        change_map,
        diff_components_path
    )
