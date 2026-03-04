import json
import re
from functools import reduce
from typing import Dict, Set, List
import networkx as nx


def parse_diff_file_novels(diff_path: str) -> Set[int]:
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


def parse_diff_file_unchangeds(diff_path: str) -> Dict[int,int]:
    """
    Parse the diff file and extract all pairs of Java uniqueIds that are matched.

    Args:
        diff_path: Path to the diff text file
    """
    paired_ids = dict()

    with open(diff_path, 'r') as f:
        content = f.readlines()

    # Extract all Novel entries (format: "number: Novel")
    unchanged_pattern = r'^(\d+): Unchanged\(ID: (\d+)\),\s$' #r'(\d+):\s*Novel'
    for line in content:
        match = re.match(unchanged_pattern,line)
        if match:
            paired_ids[int(match[1])] = int(match[2])

    return paired_ids

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
    novel_unique_ids = parse_diff_file_novels(diff_path)
    
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
    # external_edges = filter_internal_edges(filtered_edges, parent_map, novel_unique_ids)
    external_edges = filtered_edges

    # Find connected components as lists of edges
    components = find_connected_components(external_edges)
    
    return components, unique_id_to_info, parent_map, novel_unique_ids


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


def compute_node_reachability(components: List[List[dict]],
                              unique_id_to_info: Dict[int, dict],
                              parent_map: Dict[int, int],
                              novel_unique_ids: Set[int],
                              kind: str) -> List[dict]:
    """
    For each component, collapse nodes to their largest novel ancestor (the colored
    chunks), then compute how many other collapsed nodes can reach each one.

    Args:
        components: List of connected components (lists of edge dicts with fromUniqueId/toUniqueId)
        unique_id_to_info: Mapping from uniqueId to node information
        parent_map: Mapping from child uniqueId to parent uniqueId
        novel_unique_ids: Set of novel node uniqueIds
        kind: 'Removal' or 'Addition'

    Returns:
        List of component dicts, each containing 'kind' and 'nodes' with reachability counts
    """
    result_components = []
    cache = {}

    for component_edges in components:
        # Collapse each edge endpoint to its largest novel ancestor
        collapsed_graph = nx.DiGraph()
        for edge in component_edges:
            from_collapsed = find_largest_novel_ancestor(edge['fromUniqueId'], parent_map, novel_unique_ids, cache)
            to_collapsed = find_largest_novel_ancestor(edge['toUniqueId'], parent_map, novel_unique_ids, cache)
            if from_collapsed != to_collapsed:  # skip self-loops
                collapsed_graph.add_edge(from_collapsed, to_collapsed)
            else:
                # Still add the node even if the edge collapses to a self-loop
                collapsed_graph.add_node(from_collapsed)

        nodes_list = []
        for node_id in collapsed_graph.nodes():
            # Count how many other collapsed nodes can reach this node
            ancestors = nx.ancestors(collapsed_graph, node_id)

            info = unique_id_to_info.get(node_id, {})
            nodes_list.append({
                'java_id': node_id,
                'type': f"{info.get('nodeClass', 'Unknown')} ({info.get('node_kind', 'unknown')})",
                'content': info.get('content', 'N/A'),
                'position': info.get('position', 'N/A'),
                'file': info.get('file', 'Unknown'),
                'reachable_by': len(ancestors)
            })

        # Sort nodes: most reachable first, then by java_id for stability
        nodes_list.sort(key=lambda n: (-n['reachable_by'], n['java_id']))

        result_components.append({
            'kind': kind,
            'nodes': nodes_list
        })

    return result_components


def create_diff_nodes(
        lhs_results: tuple,
        rhs_results: tuple,
        unify_comps: list,
        output_path: str
):
    """
    Create a JSON file with nodes per component and their reachability counts.
    Components are merged based on unify_comps links.

    Args:
        lhs_results: Tuple of (components, unique_id_to_info, parent_map, novel_ids) for LHS
        rhs_results: Tuple of (components, unique_id_to_info, parent_map, novel_ids) for RHS
        unify_comps: List of (rhs_id, lhs_id) tuples indicating nodes that should be in same component
        output_path: Path for the output JSON file
    """
    lhs_components, lhs_unique_id_to_info, lhs_parent_map, lhs_novel_ids = lhs_results
    rhs_components, rhs_unique_id_to_info, rhs_parent_map, rhs_novel_ids = rhs_results

    lhs_node_components = compute_node_reachability(lhs_components, lhs_unique_id_to_info, lhs_parent_map,
                                                    lhs_novel_ids, 'Removal')
    rhs_node_components = compute_node_reachability(rhs_components, rhs_unique_id_to_info, rhs_parent_map,
                                                    rhs_novel_ids, 'Addition')

    # Add 'kind' field to each node
    for comp in lhs_node_components:
        for node in comp['nodes']:
            node['kind'] = 'Removal'
    
    for comp in rhs_node_components:
        for node in comp['nodes']:
            node['kind'] = 'Addition'

    # Build collapsed node to component index mapping
    lhs_node_to_comp = {}
    for comp_idx, component in enumerate(lhs_node_components):
        for node in component['nodes']:
            lhs_node_to_comp[node['java_id']] = comp_idx
    
    rhs_node_to_comp = {}
    for comp_idx, component in enumerate(rhs_node_components):
        for node in component['nodes']:
            rhs_node_to_comp[node['java_id']] = comp_idx
    
    # Union-find to merge components
    parent = list(range(len(lhs_node_components) + len(rhs_node_components)))
    
    def find(x):
        if parent[x] != x:
            parent[x] = find(parent[x])
        return parent[x]
    
    def union(x, y):
        px, py = find(x), find(y)
        if px != py:
            parent[px] = py
    
    # Merge components based on unify_comps (these need to be collapsed to largest novel ancestor)
    cache = {}
    for rhs_id, lhs_id in unify_comps:
        # Collapse to largest novel ancestor
        rhs_collapsed = find_largest_novel_ancestor(rhs_id, rhs_parent_map, rhs_novel_ids, cache)
        lhs_collapsed = find_largest_novel_ancestor(lhs_id, lhs_parent_map, lhs_novel_ids, cache)
        
        if rhs_collapsed in rhs_node_to_comp and lhs_collapsed in lhs_node_to_comp:
            rhs_comp_idx = rhs_node_to_comp[rhs_collapsed] + len(lhs_node_components)
            lhs_comp_idx = lhs_node_to_comp[lhs_collapsed]
            union(lhs_comp_idx, rhs_comp_idx)
    
    # Group components by their root
    merged_components = {}
    for idx in range(len(parent)):
        root = find(idx)
        if root not in merged_components:
            merged_components[root] = []
        
        if idx < len(lhs_node_components):
            merged_components[root].extend(lhs_node_components[idx]['nodes'])
        else:
            merged_components[root].extend(rhs_node_components[idx - len(lhs_node_components)]['nodes'])
    
    # Create final output
    all_components = []
    for comp_nodes in merged_components.values():
        all_components.append({
            'nodes': comp_nodes
        })
    
    for idx, component in enumerate(all_components, start=1):
        component['component_id'] = idx

    output = {'diff_components': all_components}

    with open(output_path, 'w') as f:
        json.dump(output, f, indent=2)


def generate_rename_name(
        lhs_node: dict, lhs_parent: dict, lhs_tree: dict,
        rhs_node: dict, rhs_parent: dict, rhs_tree: dict) -> str:
    parent_class = lhs_parent['nodeClass']
    assert(lhs_node['nodeClass'] == 'SimpleName' and rhs_node['nodeClass'] == 'SimpleName')
    lhs_name = lhs_node['content']
    rhs_name = rhs_node['content']
    if parent_class == 'TypeDeclaration':
        renamed_kind = 'type'
    elif parent_class == 'VariableDeclarationFragment':
        renamed_kind = 'variable'
    elif parent_class == 'SingleVariableDeclaration':
        _, lhs_grandparent = find_id(lhs_tree, lhs_parent['uniqueId'])
        assert lhs_grandparent['nodeClass'] == 'Structural'
        _, lhs_greatgrandparent = find_id(lhs_tree, lhs_grandparent['uniqueId'])
        grandparent_class = lhs_greatgrandparent['nodeClass']
        if grandparent_class == 'MethodDeclaration':
            assert lhs_greatgrandparent['children'][0]['nodeClass'] == 'SimpleName'
            method_name = lhs_greatgrandparent['children'][0]['content']
            renamed_kind = f'parameter of method {method_name}'
        else:
            assert False, grandparent_class
    elif parent_class == 'MethodDeclaration':
        renamed_kind = 'method'
    else:
        assert False, parent_class
    return f"{renamed_kind} renamed from {lhs_name} to {rhs_name}"

def find_descendant(node: dict, node_class: str) -> dict | None:
    """Find the first descendant node with the given nodeClass (depth-first)."""
    if node.get('nodeClass') == node_class:
        return node
    for child in node.get('children', []):
        result = find_descendant(child, node_class)
        if result is not None:
            return result
    return None


def generate_addrem_name(node: dict, add: bool, tree: dict) -> str:
    action = 'added' if add else 'removed'
    node_class = node['nodeClass']
    if node_class == 'VariableDeclarationStatement':
        added_kind = 'variable'
        name_loc = node['children'][1]['children'][0]['children'][0]
    elif node_class == 'MethodDeclaration':
        added_kind = 'method'
        name_loc = node['children'][0]
    elif node_class == 'PrimitiveType' or node_class == node_class == 'SimpleType': #TODO kill me
        added_kind = ''
        _, parent = find_id(tree, node['uniqueId'])
        assert parent['nodeClass'] == 'VariableDeclarationStatement' or parent['nodeClass'] == 'FieldDeclaration'
        if parent['nodeClass'] == 'VariableDeclarationStatement':
            name_loc = parent['children'][1]['children'][0]['children'][0]
        else:
            # FieldDeclaration: find VariableDeclarationFragment (may be nested under Structural)
            vdf = find_descendant(parent, 'VariableDeclarationFragment')
            assert vdf is not None, f'No VariableDeclarationFragment found in FieldDeclaration {parent.get("uniqueId")}'
            name_loc = vdf['children'][0]
    elif node_class == 'FieldDeclaration':
        added_kind = 'field'
        # Find VariableDeclarationFragment (may be nested under Structural after modifier additions)
        vdf = find_descendant(node, 'VariableDeclarationFragment')
        assert vdf is not None, f'No VariableDeclarationFragment found in FieldDeclaration {node.get("uniqueId")}'
        name_loc = vdf['children'][0]
    elif node_class == 'SingleVariableDeclaration':
        added_kind = 'parameter'
        name_loc = node['children'][0]
    elif node_class == 'TypeParameter':
        added_kind = 'type parameter'
        name_loc = node['children'][0]
        tree = rhs_tree if add else lhs_tree
        _, tp_parent = find_id(tree, node['uniqueId'])
        if tp_parent and tp_parent['nodeClass'] == 'Structural':
            _, tp_grandparent = find_id(tree, tp_parent['uniqueId'])
            if tp_grandparent and tp_grandparent['nodeClass'] in ('TypeDeclaration', 'MethodDeclaration'):
                owner_name = tp_grandparent['children'][0]['content']
                assert name_loc['nodeClass'] == 'SimpleName'
                return f'{added_kind} {name_loc['content']} {action} in {owner_name}'
    elif node_class == 'ImportDeclaration':
        added_kind = 'import'
        name_loc = node['children'][0]
    else:
        assert False, node_class
    assert name_loc['nodeClass'] == 'SimpleName' or name_loc['nodeClass'] == 'QualifiedName' #TODO, fix
    base_name = f'{added_kind} {name_loc["content"]} {action}'
    # Find enclosing method or class by walking up ancestors
    current_node = node
    while current_node:
        _, ancestor = find_id(tree, current_node['uniqueId'])
        if ancestor is None:
            break
        if ancestor['nodeClass'] == 'MethodDeclaration':
            method_name = ancestor['children'][0]['content']
            base_name += f' in {method_name}'
            break
        if ancestor['nodeClass'] in ('TypeDeclaration', 'EnumDeclaration', 'AnnotationTypeDeclaration',
                                     'RecordDeclaration', 'AnonymousClassDeclaration'):
            # children[0] is the SimpleName for named types; AnonymousClassDeclaration has no name
            if ancestor['nodeClass'] != 'AnonymousClassDeclaration' and ancestor.get('children'):
                class_name = ancestor['children'][0]['content']
                base_name += f' in {class_name}'
            break
        current_node = ancestor
    return base_name

def create_diff_components(
    lhs_results: tuple,
    rhs_results: tuple,
    unify_comps: list,
    lhs_tree: dict,
    rhs_tree: dict,
    output_path: str
):
    """
    Create the final diff_components JSON with LHS (Removal) and RHS (Addition) changes.
    Components are merged based on unify_comps links.
    
    Args:
        lhs_results: Tuple of (components, unique_id_to_info, ...) for LHS
        rhs_results: Tuple of (components, unique_id_to_info, ...) for RHS
        unify_comps: List of (rhs_id, lhs_id) tuples indicating nodes that should be in same component
        output_path: Path for the output JSON file
    """
    lhs_components, lhs_unique_id_to_info, _, _ = lhs_results
    rhs_components, rhs_unique_id_to_info, _, _ = rhs_results
    
    # Convert LHS and RHS components to JSON
    lhs_components_json = components_to_json_structure(lhs_components, lhs_unique_id_to_info)
    rhs_components_json = components_to_json_structure(rhs_components, rhs_unique_id_to_info)
    
    # Build node to component index mapping
    lhs_node_to_comp = {}
    for comp_idx, component in enumerate(lhs_components_json):
        for edge in component:
            lhs_node_to_comp[edge['from']['java_id']] = comp_idx
            lhs_node_to_comp[edge['to']['java_id']] = comp_idx
    
    rhs_node_to_comp = {}
    for comp_idx, component in enumerate(rhs_components_json):
        for edge in component:
            rhs_node_to_comp[edge['from']['java_id']] = comp_idx
            rhs_node_to_comp[edge['to']['java_id']] = comp_idx
    
    # Union-find to merge components
    # Each component gets a unique ID: lhs components are 0..len(lhs)-1, rhs are len(lhs)..len(lhs)+len(rhs)-1
    parent = list(range(len(lhs_components_json) + len(rhs_components_json)))
    
    def find(x):
        if parent[x] != x:
            parent[x] = find(parent[x])
        return parent[x]
    
    def union(x, y):
        px, py = find(x), find(y)
        if px != py:
            parent[px] = py
    
    # Merge components based on unify_comps
    for rhs_id, lhs_id in unify_comps:
        if rhs_id in rhs_node_to_comp and lhs_id in lhs_node_to_comp:
            rhs_comp_idx = rhs_node_to_comp[rhs_id] + len(lhs_components_json)
            lhs_comp_idx = lhs_node_to_comp[lhs_id]
            union(lhs_comp_idx, rhs_comp_idx)
    
    # Group components by their root
    merged_components = {}
    for idx in range(len(parent)):
        root = find(idx)
        if root not in merged_components:
            merged_components[root] = {'lhs': [], 'rhs': []}
        if idx < len(lhs_components_json):
            merged_components[root]['lhs'].append(lhs_components_json[idx])
        else:
            merged_components[root]['rhs'].append(rhs_components_json[idx - len(lhs_components_json)])
    
    # Create final output
    final_components = []
    component_id = 1
    
    for comp_data in merged_components.values():
        component_changes = []
        
        # Add all LHS edges as removals
        for lhs_component in comp_data['lhs']:
            for edge in lhs_component:
                edge_with_kind = edge.copy()
                edge_with_kind['kind'] = 'Removal'
                component_changes.append(edge_with_kind)
        
        # Add all RHS edges as additions
        for rhs_component in comp_data['rhs']:
            for edge in rhs_component:
                edge_with_kind = edge.copy()
                edge_with_kind['kind'] = 'Addition'
                component_changes.append(edge_with_kind)

        def get_kind() -> str:
            if comp_data['lhs'] and comp_data['rhs']:
                return 'rename'
            elif comp_data['lhs']:
                return 'delete'
            else:
                return 'add'

        def get_all_ancestors(tree: dict, node: dict) -> list[dict]:
            res = []
            while node:
                node, parent = find_id(tree, node['uniqueId'])
                if parent: res.append(parent)
                node = parent
            return res

        if component_changes:
            component_kind = get_kind()
            component_names = []
            tos = {c['to']['java_id'] for c in component_changes}
            if component_kind == 'rename':
                # find the matched sink(s)
                sinks_matched = list(filter(lambda sinks: sinks[0] in tos or sinks[1] in tos, unify_comps))
                for (rhs_sink, lhs_sink) in sinks_matched:
                    rhs_node, rhs_parent = find_id(rhs_tree,rhs_sink)
                    lhs_node, lhs_parent = find_id(lhs_tree,lhs_sink)
                    component_names.append(generate_rename_name(lhs_node, lhs_parent, lhs_tree, rhs_node, rhs_parent, rhs_tree))
            elif component_kind == 'add':
                added_sinks = set(filter(lambda x: x in tos, unmatched_sinks))
                for added_sink in added_sinks:
                    rhs_node, rhs_parent = find_id(rhs_tree, added_sink)
                    ancestors = [rhs_parent] + get_all_ancestors(rhs_tree, rhs_parent)
                    if set(a['uniqueId'] for a in ancestors).intersection(added_sinks):
                        continue
                    component_names.append(generate_addrem_name(rhs_node, True, rhs_tree))
            elif component_kind == 'delete':
                del_sinks = set(filter(lambda x: x in tos, unmatched_sinks))
                for del_sink in del_sinks:
                    lhs_node, lhs_parent = find_id(lhs_tree, del_sink)
                    ancestors = [lhs_parent] + get_all_ancestors(lhs_tree, lhs_parent)
                    if set(a['uniqueId'] for a in ancestors).intersection(del_sinks):
                        continue
                    component_names.append(generate_addrem_name(lhs_node, False, lhs_tree))
            else:
                raise Exception()

            final_component_name = ', '.join(component_names)
            final_component_name = final_component_name[0].upper() + final_component_name[1:] if final_component_name else ''
            final_components.append({
                'component_id': component_id,
                'component_name': final_component_name,
                'component_kind': component_kind,
                'changes': component_changes
            })
            component_id += 1
    
    output = {'diff_components': final_components}
    
    with open(output_path, 'w') as f:
        json.dump(output, f, indent=2)
    
    print(f"\nDiff components JSON written to {output_path}")
    print(f"  Total components after merging: {len(final_components)}")
    print(f"  Original LHS components: {len(lhs_components_json)}")
    print(f"  Original RHS components: {len(rhs_components_json)}")


if __name__ == "__main__":
    import os
    base_path = os.path.join(os.path.dirname(__file__), "..", "difftastic", "Files")
    lhs_json_path = os.path.join(base_path, "lhs.json")
    rhs_json_path = os.path.join(base_path, "rhs.json")
    diff_path = os.path.join(base_path, "result.txt")
    diff_components_path = os.path.join(base_path, "diff_components.json")
    diff_nodes_path = os.path.join(base_path, "diff_nodes.json")
    
    # Process LHS and RHS
    lhs_results = filter_novel_edges(lhs_json_path, diff_path)
    rhs_results = filter_novel_edges(rhs_json_path, diff_path)

    novels = parse_diff_file_novels(diff_path)

    pairs = parse_diff_file_unchangeds(diff_path)




    with open(lhs_json_path, 'r') as f:
        lhs_tree = json.load(f)['syntax']
    with open(rhs_json_path, 'r') as f:
        rhs_tree = json.load(f)['syntax']

    def find_id(tree: dict, id: int) -> (dict | None, dict | None):
        def find_id_internal(tree: dict, parent: dict) -> (dict | None, dict | None):
            if tree['uniqueId'] == id:
                return (tree, parent)
            else:
                if 'children' not in tree:
                    return (None, None)
                for child in tree['children']:
                    (node,parent) = find_id_internal(child,tree)
                    if node:
                        return (node, parent)
                return (None, None)

        for cu in tree.values():
            (node,parent) = find_id_internal(cu, None)
            if node:
                return (node, parent)
        return (None, None)

    def get_sinks(component: List[dict]) -> set[int]:
        froms = set(e['fromUniqueId'] for e in component)
        return set(e['toUniqueId'] for e in component if e['toUniqueId'] not in froms)

    # for lhs_comp in lhs_results[0]:
    #     sinks = get_sinks(lhs_comp)
    def get_matches(first: list[dict],second: list[dict], matches:dict[int,int]) -> list[(int,int)]:
        '''
        Match two lists with a matching dict `matches`,
        :returns: the indices that match in each list
        '''
        fidx = 0
        sidx = 0
        res = []
        while fidx < len(first) and sidx < len(second):
            if first[fidx]['uniqueId'] in matches:
                if second[sidx]['uniqueId'] == matches[first[fidx]['uniqueId']]:
                    res.append((fidx,sidx))
                    fidx += 1

                else:
                    assert second[sidx]['uniqueId'] not in matches
                sidx += 1
            else:
                fidx += 1
        return [(-1,-1)] + res + [(len(first),len(second))]


    rhs_all_sinks = reduce(lambda s1,s2: s1.union(s2), (get_sinks(c) for c in rhs_results[0]), set())
    lhs_all_sinks = reduce(lambda s1,s2: s1.union(s2), (get_sinks(c) for c in lhs_results[0]), set())

    unify_comps = []
    # We're looking for symmetrical things so you can only do it on one side and not the other, right?
    for rhs_comp in rhs_results[0]:
        sinks = get_sinks(rhs_comp)

        for sink_id in sinks:
            (node,parent) = find_id(rhs_tree, sink_id)
            assert node
            for child in (node['children'] if 'children' in node else []):
                if child['uniqueId'] in pairs:
                    #if novel on the other side, if not:
                    pass
            if parent and parent['uniqueId'] in pairs:
                parent_match_id = pairs[parent['uniqueId']]
                (parent_match,_) = find_id(lhs_tree,parent_match_id)
                alignments = get_matches(parent['children'],parent_match['children'],pairs)
                for (start,end) in zip(alignments, alignments[1:]):
                    range_this = (n['uniqueId'] for n in  parent['children'][start[0]+1:end[0]] if n['uniqueId'] in rhs_all_sinks)
                    range_match = (n['uniqueId'] for n in parent_match['children'][start[1]+1:end[1]] if n['uniqueId'] in lhs_all_sinks)
                    unify_comps.extend(zip(range_this,range_match))

    unmatched_sinks = set()
    unmatched_sinks.update(filter(lambda s: s not in [c[1] for c in unify_comps], lhs_all_sinks))
    unmatched_sinks.update(filter(lambda s: s not in [c[0] for c in unify_comps], rhs_all_sinks))

    # Create diff_components.json with merged components based on unify_comps
    create_diff_components(lhs_results, rhs_results, unify_comps, lhs_tree, rhs_tree, diff_components_path)

    # Create diff_nodes.json with node reachability counts per merged component
    create_diff_nodes(lhs_results, rhs_results, unify_comps, diff_nodes_path)
