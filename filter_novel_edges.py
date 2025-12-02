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


def build_unique_id_to_id_mapping(syntax_tree: dict) -> Dict[int, int]:
    """
    Traverse the syntax tree and build a mapping from uniqueId to id.
    
    Args:
        syntax_tree: The syntax tree structure
        
    Returns:
        Dictionary mapping uniqueId to id
    """
    mapping = {}
    
    def traverse(node):
        if isinstance(node, dict):
            # If this node has both uniqueId and id, add to mapping
            if 'uniqueId' in node and 'id' in node:
                mapping[node['uniqueId']] = node['id']
            
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
    return mapping


def build_id_to_node_info_mapping(syntax_tree: dict) -> Dict[int, dict]:
    """
    Build a mapping from id to node information (content, position, nodeClass, uniqueId).
    
    Args:
        syntax_tree: The syntax tree structure
        
    Returns:
        Dictionary mapping id to node info
    """
    mapping = {}
    
    def traverse(node):
        if isinstance(node, dict):
            if 'id' in node:
                node_id = node['id']
                info = {
                    'content': node.get('content', node.get('open_content', '')),
                    'position': node.get('position', node.get('open_position', '')),
                    'nodeClass': node.get('nodeClass', 'Unknown'),
                    'node_kind': node.get('node_kind', 'unknown'),
                    'uniqueId': node.get('uniqueId', -1)
                }
                mapping[node_id] = info
            
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
    return mapping


def find_connected_components(edges: List[dict]) -> List[Set[int]]:
    """
    Find connected components using NetworkX.
    
    Args:
        edges: List of edge dictionaries with 'fromId' and 'toId'
        
    Returns:
        List of sets, where each set contains node IDs in a connected component
    """
    # Create an undirected graph
    G = nx.Graph()
    
    # Add edges to the graph
    for edge in edges:
        G.add_edge(edge['fromId'], edge['toId'])
    
    # Find connected components
    components = list(nx.connected_components(G))
    
    return components


def filter_novel_edges(json_path: str, diff_path: str, output_path: str, source_label: str = ""):
    """
    Main function to filter edges where both endpoints are novel.
    
    Args:
        json_path: Path to the JSON file with edges and syntax tree
        diff_path: Path to the diff text file
        output_path: Path to write the filtered edges
        source_label: Label to identify the source (e.g., "LHS" or "RHS")
    """
    # Load JSON data
    with open(json_path, 'r') as f:
        data = json.load(f)
    
    novel_ids = parse_diff_file(diff_path)
    
    unique_id_to_id = build_unique_id_to_id_mapping(data.get('syntax', {}))
    
    all_edges = data.get('nameResEdges', [])
    
    # Filter edges and translate IDs
    filtered_edges = []
    seen_edges = set()  # For duplicate detection
    
    for edge in all_edges:
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
    
    # Build id to node info mapping
    id_to_info = build_id_to_node_info_mapping(data.get('syntax', {}))
    
    components = find_connected_components(filtered_edges)
    
    return filtered_edges, components, id_to_info


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
        lhs_results: Tuple of (filtered_edges, components, id_to_info) for LHS
        rhs_results: Tuple of (filtered_edges, components, id_to_info) for RHS
        output_path: Base path for output files
    """
    lhs_edges, lhs_components, lhs_id_to_info = lhs_results
    rhs_edges, rhs_components, rhs_id_to_info = rhs_results
    
    # Create structured output with lhs and rhs fields
    structured_output = {
        'lhs': lhs_edges,
        'rhs': rhs_edges
    }
    
    # Save structured edges
    with open(output_path, 'w') as f:
        json.dump(structured_output, f, indent=2)
    
    print(f"\nStructured filtered edges written to {output_path}")
    print(f"  LHS edges: {len(lhs_edges)}")
    print(f"  RHS edges: {len(rhs_edges)}")
    print(f"  Total edges: {len(lhs_edges) + len(rhs_edges)}")
    
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
    
    print(f"Components JSON written to {components_json_path}")
    print(f"  LHS components: {len(lhs_components)}")
    print(f"  RHS components: {len(rhs_components)}")


if __name__ == "__main__":
    # File paths
    base_path = r"C:\Users\Bitroix\Desktop\Technion\Diff\difftastic\Files"
    lhs_json_path = base_path + r"\lhs.json"
    rhs_json_path = base_path + r"\rhs.json"
    diff_path = base_path + r"\result.txt"
    output_path = base_path + r"\output.json"
    
    # Process LHS
    lhs_results = filter_novel_edges(lhs_json_path, diff_path, output_path, "LHS")
    
    # Process RHS
    rhs_results = filter_novel_edges(rhs_json_path, diff_path, output_path, "RHS")
    
    # Combine and save results
    combine_and_save_results(lhs_results, rhs_results, output_path)
