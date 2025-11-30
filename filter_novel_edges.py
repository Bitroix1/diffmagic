import json
import re
from typing import Dict, Set, List, Tuple


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


def filter_novel_edges(json_path: str, diff_path: str, output_path: str):
    """
    Main function to filter edges where both endpoints are novel.
    
    Args:
        json_path: Path to the JSON file with edges and syntax tree
        diff_path: Path to the diff text file
        output_path: Path to write the filtered edges
    """
    # Load JSON data
    with open(json_path, 'r') as f:
        data = json.load(f)
    
    # Parse diff file to get novel IDs
    novel_ids = parse_diff_file(diff_path)
    print(f"Found {len(novel_ids)} novel IDs in diff file")
    print(f"Novel IDs (first 20): {sorted(list(novel_ids))[:20]}")
    
    # Build mapping from uniqueId to id
    unique_id_to_id = build_unique_id_to_id_mapping(data.get('syntax', {}))
    print(f"Built mapping with {len(unique_id_to_id)} entries")
    print(f"Sample mappings (first 5): {dict(list(unique_id_to_id.items())[:5])}")
    
    # Process edges
    all_edges = []
    edge_categories = ['nameResEdges']  # Add other edge types if they exist
    
    # Collect all edge types from the JSON
    for key in data.keys():
        if key != 'syntax' and isinstance(data[key], list):
            edge_categories.append(key)
    
    edge_categories = list(set(edge_categories))  # Remove duplicates
    
    for edge_type in edge_categories:
        if edge_type in data:
            all_edges.extend(data[edge_type])
    
    print(f"Processing {len(all_edges)} total edges")
    
    # Filter edges and translate IDs
    filtered_edges = []
    seen_edges = set()  # For duplicate detection
    
    print(f"\n=== Processing Edges ===")
    for idx, edge in enumerate(all_edges):
        from_unique_id = edge.get('fromId')
        to_unique_id = edge.get('toId')
        
        if idx < 5:  # Print first 5 edges for debugging
            print(f"\nEdge {idx + 1}:")
            print(f"  from: {edge.get('from')}")
            print(f"  to: {edge.get('to')}")
            print(f"  fromUniqueId: {from_unique_id}")
            print(f"  toUniqueId: {to_unique_id}")
        
        # Translate unique IDs to incremental IDs
        from_id = unique_id_to_id.get(from_unique_id)
        to_id = unique_id_to_id.get(to_unique_id)
        
        if idx < 5:
            print(f"  fromId (translated): {from_id}")
            print(f"  toId (translated): {to_id}")
            print(f"  from is novel: {from_id in novel_ids if from_id else 'N/A'}")
            print(f"  to is novel: {to_id in novel_ids if to_id else 'N/A'}")
        
        # Skip if we can't find the mapping
        if from_id is None or to_id is None:
            if idx < 5:
                print(f"  SKIPPED: Missing ID mapping")
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
                if idx < 5:
                    print(f"  ✓ INCLUDED (both sides novel)")
            else:
                if idx < 5:
                    print(f"  SKIPPED: Duplicate edge")
        else:
            if idx < 5:
                print(f"  SKIPPED: Not both sides novel")
    
    print(f"Filtered to {len(filtered_edges)} edges with both sides novel (duplicates removed)")
    
    # Write output - just the edges list
    with open(output_path, 'w') as f:
        json.dump(filtered_edges, f, indent=2)
    
    print(f"Results written to {output_path}")


if __name__ == "__main__":
    # Example file paths - replace with actual paths
    json_path = r"C:\Users\Bitroix\Desktop\Technion\Diff\difftastic\Files\lhs.json"
    diff_path = r"C:\Users\Bitroix\Desktop\Technion\Diff\difftastic\Files\diff.txt"
    output_path = r"C:\Users\Bitroix\Desktop\Technion\Diff\difftastic\Files\output.json"
    
    filter_novel_edges(json_path, diff_path, output_path)
