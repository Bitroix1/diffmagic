import json
import os
import shutil
from difflib import unified_diff


def parse_position(position: str) -> tuple:
    """
    Extract line number and character range from position string.
    Position format: "line:col-col"
    
    Args:
        position: Position string like "5:8-11"
        
    Returns:
        Tuple of (line_number, start_col, end_col)
    """
    if ':' in position and '-' in position:
        line_part, col_part = position.split(':')
        start_col, end_col = col_part.split('-')
        return int(line_part), int(start_col), int(end_col)
    return 0, 0, 0


def get_affected_positions(component: list) -> dict:
    """
    Get all positions affected by a component with their content.
    
    Args:
        component: List of edges in the component
        
    Returns:
        Dict mapping line numbers to list of (start_col, end_col, content) tuples
    """
    positions = {}
    for edge in component:
        from_data = edge.get('from', {})
        to_data = edge.get('to', {})
        
        from_pos = from_data.get('position', '')
        to_pos = to_data.get('position', '')
        from_content = from_data.get('content', '')
        to_content = to_data.get('content', '')
        
        if from_pos:
            line_num, start_col, end_col = parse_position(from_pos)
            if line_num not in positions:
                positions[line_num] = []
            positions[line_num].append((start_col, end_col, from_content))
        
        if to_pos:
            line_num, start_col, end_col = parse_position(to_pos)
            if line_num not in positions:
                positions[line_num] = []
            positions[line_num].append((start_col, end_col, to_content))
    
    # Remove duplicates and sort by start column
    for line_num in positions:
        positions[line_num] = sorted(list(set(positions[line_num])))
    
    return positions


def remove_words_from_line(line: str, positions: list) -> str:
    """
    Remove words at specified positions from a line.
    
    Args:
        line: The line to modify
        positions: List of (start_col, end_col, content) tuples
        
    Returns:
        Modified line with words removed
    """
    # Sort positions by start column in reverse order to avoid index shifting
    sorted_positions = sorted(positions, key=lambda x: x[0], reverse=True)
    
    result = line
    for start_col, end_col, content in sorted_positions:
        if start_col < len(result) and end_col <= len(result):
            result = result[:start_col] + result[end_col:]
    
    return result


def add_words_to_line(line: str, positions: list) -> str:
    """
    Add words at specified positions to a line.
    For RHS, we need to add the new content at the positions.
    
    Args:
        line: The base line (without the additions)
        positions: List of (start_col, end_col, content) tuples
        
    Returns:
        Modified line with words added
    """
    # Sort positions by start column
    sorted_positions = sorted(positions, key=lambda x: x[0])
    
    result = line
    offset = 0
    for start_col, end_col, content in sorted_positions:
        insert_pos = start_col + offset
        result = result[:insert_pos] + content + result[insert_pos:]
        offset += len(content)
    
    return result


def generate_component_diff(lhs_lines: list, rhs_lines: list, component: list, 
                           filename: str, output_path: str, side: str):
    """
    Generate a diff file for a specific component.
    
    Args:
        lhs_lines: Lines from lhs file
        rhs_lines: Lines from rhs file
        component: The component data with position information
        filename: Name of the source file
        output_path: Path where the diff should be written
        side: 'lhs' or 'rhs' to indicate which side the component is from
    """
    # Get affected positions with content
    positions = get_affected_positions(component)
    
    if not positions:
        return False
    
    context_lines = 3
    changed_lines = sorted(positions.keys())
    
    # Build hunks with context
    hunks = []
    current_hunk = None
    
    for line_num in changed_lines:
        hunk_start = max(0, line_num - context_lines)
        hunk_end = min(len(lhs_lines) - 1, line_num + context_lines)
        
        # Check if we should merge with current hunk
        if current_hunk and hunk_start <= current_hunk['end'] + 1:
            # Extend current hunk
            current_hunk['end'] = hunk_end
            current_hunk['changed_lines'].add(line_num)
        else:
            # Start new hunk
            if current_hunk:
                hunks.append(current_hunk)
            current_hunk = {
                'start': hunk_start,
                'end': hunk_end,
                'changed_lines': {line_num}
            }
    
    # Add last hunk
    if current_hunk:
        hunks.append(current_hunk)
    
    # Generate diff lines
    diff_lines = []
    
    # Add appropriate header
    diff_lines.append(f'--- a/C:/Users/Bitroix/Desktop/Technion/Diff/difftastic/Files/lhs/{filename}')
    diff_lines.append(f'+++ b/C:/Users/Bitroix/Desktop/Technion/Diff/difftastic/Files/rhs/{filename}')
    
    # Generate each hunk
    for hunk in hunks:
        hunk_start = hunk['start']
        hunk_end = hunk['end']
        changed_lines = hunk['changed_lines']
        
        # Calculate line counts
        line_count = hunk_end - hunk_start + 1
        
        # Add hunk header
        diff_lines.append(f'@@ -{hunk_start+1},{line_count} +{hunk_start+1},{line_count} @@')
        
        # Add lines in hunk
        for i in range(hunk_start, hunk_end + 1):
            if i < len(lhs_lines) and i < len(rhs_lines):
                if i in changed_lines:
                    # This is a changed line
                    lhs_line = lhs_lines[i]
                    rhs_line = rhs_lines[i]
                    
                    if side == 'lhs':
                        # LHS: Show removal of words
                        modified_line = remove_words_from_line(lhs_line, positions[i])
                        diff_lines.append(f'-{lhs_line}')
                        diff_lines.append(f'+{modified_line}')
                    else:  # rhs
                        # RHS: Show addition of words
                        modified_line = remove_words_from_line(lhs_line, positions[i])
                        diff_lines.append(f'-{modified_line}')
                        diff_lines.append(f'+{rhs_line}')
                else:
                    # Context line - use lhs_line (they should be the same)
                    diff_lines.append(f' {lhs_lines[i]}')
    
    # Write diff to file if there are actual changes
    if len(diff_lines) > 2:  # More than just headers
        with open(output_path, 'w') as f:
            for line in diff_lines:
                f.write(line + '\n')
        print(f"  Generated: {output_path}")
        return True
    return False


def generate_diffs_from_output(output_json_path: str, lhs_dir: str, rhs_dir: str, output_diff_dir: str):
    """
    Generate separate .diff files for each component in output.json.
    
    Args:
        output_json_path: Path to output.json
        lhs_dir: Directory containing lhs files
        rhs_dir: Directory containing rhs files
        output_diff_dir: Directory where diff files should be written
    """
    # Clear and recreate output directory
    if os.path.exists(output_diff_dir):
        shutil.rmtree(output_diff_dir)
    os.makedirs(output_diff_dir, exist_ok=True)
    
    # Load output.json
    with open(output_json_path, 'r') as f:
        data = json.load(f)
    
    lhs_components = data.get('lhs', [])
    rhs_components = data.get('rhs', [])
    
    if not lhs_components and not rhs_components:
        print("No components found in output.json")
        return
    
    # Get filename from first component
    filename = None
    if lhs_components and len(lhs_components[0]) > 0:
        filename = lhs_components[0][0].get('from', {}).get('file', 'Prog.java')
    elif rhs_components and len(rhs_components[0]) > 0:
        filename = rhs_components[0][0].get('from', {}).get('file', 'Prog.java')
    
    if not filename:
        print("Could not determine filename")
        return
    
    # Read lhs and rhs files
    lhs_file_path = os.path.join(lhs_dir, filename)
    rhs_file_path = os.path.join(rhs_dir, filename)
    
    with open(lhs_file_path, 'r') as f:
        lhs_lines = [line.rstrip('\n') for line in f.readlines()]
    
    with open(rhs_file_path, 'r') as f:
        rhs_lines = [line.rstrip('\n') for line in f.readlines()]
    
    diff_count = 0
    
    # Process LHS components
    print(f"\nGenerating diffs for {len(lhs_components)} LHS component(s)...")
    for i, component in enumerate(lhs_components):
        output_path = os.path.join(output_diff_dir, f"lhs_component_{i+1}.diff")
        if generate_component_diff(lhs_lines, rhs_lines, component, filename, output_path, 'lhs'):
            diff_count += 1
    
    # Process RHS components
    print(f"\nGenerating diffs for {len(rhs_components)} RHS component(s)...")
    for i, component in enumerate(rhs_components):
        output_path = os.path.join(output_diff_dir, f"rhs_component_{i+1}.diff")
        if generate_component_diff(lhs_lines, rhs_lines, component, filename, output_path, 'rhs'):
            diff_count += 1
    
    print(f"\nTotal diff files generated: {diff_count}")


if __name__ == "__main__":
    base_path = r"C:\Users\Bitroix\Desktop\Technion\Diff\difftastic\Files"
    desktop_path = r"C:\Users\Bitroix\Desktop\Technion\Diff\desktop"
    
    output_json_path = os.path.join(base_path, "output.json")
    lhs_dir = os.path.join(base_path, "lhs")
    rhs_dir = os.path.join(base_path, "rhs")
    output_diff_dir = os.path.join(desktop_path, "app", "diffs")
    
    generate_diffs_from_output(output_json_path, lhs_dir, rhs_dir, output_diff_dir)
