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
        syntax_tree: The syntax tree structure
        
    Returns:
        Tuple of (unique_id_to_id, id_to_unique_id, id_to_info) mappings
    """
    unique_id_to_id = {}
    id_to_unique_id = {}
    id_to_info = {}
    
    def traverse(node):
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
                    'uniqueId': unique_id if unique_id is not None else -1
                }
            
            # Recursively traverse children
            if 'children' in node:
                for child in node['children']:
                    traverse(child)
            
            # Traverse all other dict values
            for value in node.values():
                if isinstance(value, (dict, list)):
                    traverse(value)
        elif isinstance(node, list):
            for item in node:
                traverse(item)
    
    traverse(syntax_tree)
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


def generalize_edges(edges: List[dict], 
                    parent_map: Dict[int, int], 
                    novel_ids: Set[int],
                    id_to_unique_id: Dict[int, int]) -> List[dict]:
    """
    Generalize edges to point to the largest novel ancestor instead of inner nodes.
    
    Args:
        edges: List of edge dictionaries
        parent_map: Mapping from child id to parent id
        novel_ids: Set of novel node ids
        id_to_unique_id: Mapping from id to uniqueId
        
    Returns:
        List of generalized edges with duplicates removed
    """
    generalized_edges = []
    seen_edges = set()
    
    print("\n=== Generalizing Edges ===")
    
    for edge in edges:
        from_id = edge['fromId']
        to_id = edge['toId']
        
        # Find largest novel ancestor for both endpoints
        generalized_from = find_largest_novel_ancestor(from_id, parent_map, novel_ids, id_to_unique_id)
        generalized_to = find_largest_novel_ancestor(to_id, parent_map, novel_ids, id_to_unique_id)
        
        # Skip self-loops
        if generalized_from == generalized_to:
            print(f"  Skipped self-loop: {from_id} -> {to_id} became {generalized_from} -> {generalized_to}")
            continue
        
        # Create edge tuple for duplicate detection
        edge_tuple = (generalized_from, generalized_to)
        
        if edge_tuple not in seen_edges:
            seen_edges.add(edge_tuple)
            
            generalized_edge = edge.copy()
            generalized_edge['fromId'] = generalized_from
            generalized_edge['toId'] = generalized_to
            
            if generalized_from != from_id or generalized_to != to_id:
                print(f"  Generalized: {from_id} -> {to_id} became {generalized_from} -> {generalized_to}")
            
            generalized_edges.append(generalized_edge)
    
    return generalized_edges


def find_connected_components(edges: List[dict]) -> List[Set[int]]:
    """
    Find connected components using NetworkX.
    
    Args:
        edges: List of edge dictionaries with 'fromId' and 'toId'
        
    Returns:
        List of sets, where each set contains node IDs in a connected component
    """
    # Undirected graph
    G = nx.Graph()
    
    for edge in edges:
        G.add_edge(edge['fromId'], edge['toId'])
    
    return list(nx.connected_components(G))


def filter_novel_edges(json_path: str, diff_path: str, source_label: str = ""):
    """
    Main function to filter edges where both endpoints are novel.
    
    Args:
        json_path: Path to the JSON file with edges and syntax tree
        diff_path: Path to the diff text file
        source_label: Label to identify the source (e.g., "LHS" or "RHS")
    """
    # Load JSON data
    with open(json_path, 'r') as f:
        data = json.load(f)
    
    novel_ids = parse_diff_file(diff_path)
    
    # Build all node mappings in one pass
    unique_id_to_id, id_to_unique_id, id_to_info = build_node_mappings(data.get('syntax', {}))
    print(f"{source_label}: Built mappings with {len(unique_id_to_id)} nodes")
    
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
    
    print(f"{source_label}: Filtered to {len(filtered_edges)} edges with both sides novel (duplicates removed)")
    
    # Mapping for generalization
    parent_map = build_parent_mapping(data.get('syntax', {}))
    
    # Generalize edges to largest novel ancestors
    generalized_edges = generalize_edges(filtered_edges, parent_map, novel_ids, id_to_unique_id)
    
    components = find_connected_components(generalized_edges)
    
    return components, id_to_info


def components_to_json_structure(components: List[Set[int]], 
                                 id_to_info: Dict[int, dict]) -> List[List[dict]]:
    """
    Convert components to JSON structure with node details.
    
    Args:
        components: List of connected components (sets of node IDs)
        id_to_info: Mapping from node ID to node information
        
    Returns:
        List of components, where each component is a list of node dictionaries
    """
    json_components = []
    
    # Sort components by size (largest first)
    sorted_components = sorted(components, key=len, reverse=True)
    
    for component in sorted_components:
        component_nodes = []
        
        # Sort nodes in component by ID for consistent output
        sorted_nodes = sorted(component)
        
        for node_id in sorted_nodes:
            info = id_to_info.get(node_id, {})
            
            node_dict = {
                'rust_id': node_id,
                'java_id': info.get('uniqueId', -1),
                'type': f"{info.get('nodeClass', 'Unknown')} ({info.get('node_kind', 'unknown')})",
                'content': info.get('content', 'N/A'),
                'position': info.get('position', 'N/A')
            }
            
            component_nodes.append(node_dict)
        
        json_components.append(component_nodes)
    
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
    components_json_path = output_path.replace('.json', '_components.json')
    with open(components_json_path, 'w') as f:
        json.dump(components_json_output, f, indent=2)
    
    print(f"\nComponents JSON written to {components_json_path}")
    print(f"  LHS components: {len(lhs_components)}")
    print(f"  RHS components: {len(rhs_components)}")


if __name__ == "__main__":
    base_path = r"C:\Users\Bitroix\Desktop\Technion\Diff\difftastic\Files"
    lhs_json_path = base_path + r"\lhs.json"
    rhs_json_path = base_path + r"\rhs.json"
    diff_path = base_path + r"\result.txt"
    output_path = base_path + r"\output.json"
    
    lhs_results = filter_novel_edges(lhs_json_path, diff_path, "LHS")
    
    rhs_results = filter_novel_edges(rhs_json_path, diff_path, "RHS")
    
    combine_and_save_results(lhs_results, rhs_results, output_path)
