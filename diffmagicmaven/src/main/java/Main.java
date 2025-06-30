import org.eclipse.jdt.core.dom.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    // Base directory for input/output files
    private static final String BASE_DIR = ".." + File.separator + ".." + File.separator + "difftastic" + File.separator + "Files" + File.separator;

    // Edges in the name resolution graph
    static class Edge {
        String from;
        String to;
        int fromId;
        int toId;  // May be -1 if binding doesn't have a corresponding node

        public Edge(String from, String to, int fromId, int toId) {
            this.from = from;
            this.to = to;
            this.fromId = fromId;
            this.toId = toId;
        }
    }

    static class FileDetails {
        String fileName;
        List<Edge> nameResEdges;
        Map<String, Object> syntax;

        public FileDetails(String fileName, List<Edge> nameResEdges, Map<String, Object> syntax) {
            this.fileName = fileName;
            this.nameResEdges = nameResEdges;
            this.syntax = syntax;
        }
    }

    public static void main(String[] args) {
        try {
            File base = new File(BASE_DIR);
            System.out.println("Base absolute path: " + base.getAbsolutePath());
            // Two directories for LHS and RHS comparison
            String lhsSourceDir = BASE_DIR + "lhs";
            String rhsSourceDir = BASE_DIR + "rhs";

            // Process LHS files
            List<String> lhsJavaFiles = getAllJavaFiles(new File(lhsSourceDir));
            List<FileDetails> lhsFilesDetails = processFiles(lhsJavaFiles, 1);

            // Output LHS data
            Map<String, Object> lhsOutput = new HashMap<>();
            
            List<Edge> lhsNameResEdges = new ArrayList<>();
            Map<String, Object> lhsSyntaxes = new HashMap<>();

            // Collect data from processed files
            for (FileDetails fileDetails : lhsFilesDetails) {
                lhsNameResEdges.addAll(fileDetails.nameResEdges);
                
                String fileName = new File(fileDetails.fileName).getName();
                lhsSyntaxes.put(fileName, fileDetails.syntax);
            }

            lhsOutput.put("nameResEdges", lhsNameResEdges);
            lhsOutput.put("syntax", lhsSyntaxes);

            // Convert LHS to JSON with pretty printing
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String lhsJsonStr = gson.toJson(lhsOutput);

            // Output the LHS JSON
            System.out.println("LHS OUTPUT:");
            System.out.println(lhsJsonStr);

            // Save LHS JSON to file
            try (FileWriter file = new FileWriter(BASE_DIR + "lhs.json")) {
                file.write(lhsJsonStr);
                System.out.println("LHS JSON saved to: " + BASE_DIR + "lhs.json");
            } catch (IOException e) {
                System.err.println("Error saving LHS JSON to file: " + e.getMessage());
            }

            int lastLhsUniqueId = SyntaxVisitor.getLastUniqueId();

            // Process RHS files
            List<String> rhsJavaFiles = getAllJavaFiles(new File(rhsSourceDir));
            List<FileDetails> rhsFilesDetails = processFiles(rhsJavaFiles, lastLhsUniqueId + 1);

            // Output RHS data
            Map<String, Object> rhsOutput = new HashMap<>();
            
            List<Edge> rhsNameResEdges = new ArrayList<>();
            Map<String, Object> rhsSyntaxes = new HashMap<>();

            // Collect data from processed files
            for (FileDetails fileDetails : rhsFilesDetails) {
                rhsNameResEdges.addAll(fileDetails.nameResEdges);
                
                String fileName = new File(fileDetails.fileName).getName();
                rhsSyntaxes.put(fileName, fileDetails.syntax);
            }

            rhsOutput.put("nameResEdges", rhsNameResEdges);
            rhsOutput.put("syntax", rhsSyntaxes);

            // Convert RHS to JSON with pretty printing
            String rhsJsonStr = gson.toJson(rhsOutput);

            // Output the RHS JSON
            System.out.println("RHS OUTPUT:");
            System.out.println(rhsJsonStr);

            // Save RHS JSON to file
            try (FileWriter file = new FileWriter(BASE_DIR + "rhs.json")) {
                file.write(rhsJsonStr);
                System.out.println("RHS JSON saved to: " + BASE_DIR + "rhs.json");
            } catch (IOException e) {
                System.err.println("Error saving RHS JSON to file: " + e.getMessage());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // Recursively collect all .java files in a directory
    public static List<String> getAllJavaFiles(File directory) {
        List<String> javaFiles = new ArrayList<>();
        
        if (!directory.exists()) {
            System.err.println("Warning: Directory does not exist: " + directory.getAbsolutePath());
            return javaFiles;
        }
        
        if (!directory.isDirectory()) {
            System.err.println("Warning: Path is not a directory: " + directory.getAbsolutePath());
            return javaFiles;
        }
        
        File[] files = directory.listFiles();
        if (files == null) {
            System.err.println("Warning: Cannot access directory contents: " + directory.getAbsolutePath());
            return javaFiles;
        }
        
        for (File file : files) {
            if (file.isDirectory()) {
                javaFiles.addAll(getAllJavaFiles(file));
            } else if (file.getName().endsWith(".java")) {
                javaFiles.add(file.getAbsolutePath());
            }
        }
        return javaFiles;
    }
    
    // Helper method to process files with two-pass approach
    private static List<FileDetails> processFiles(
            List<String> javaFiles,
            int startUniqueId) throws Exception {
        List<FileDetails> filesDetails = new ArrayList<>();
        List<char[]> sources = new ArrayList<>();
        List<CompilationUnit> compilationUnits = new ArrayList<>();

        // Read all source files
        for (String filePath : javaFiles) {
            sources.add(new String(Files.readAllBytes(Paths.get(filePath))).toCharArray());
        }

        // Classpath setup
        String[] classpathEntries = { System.getProperty("java.home") + File.separator + "lib" + File.separator + "rt.jar" };
        String[] sourcepathEntries = { new File(javaFiles.get(0)).getParent() };

        SyntaxVisitor.setStartingUniqueId(startUniqueId);

        // FIRST PASS: Parse all compilation units
        System.out.println("First pass: Parsing all compilation units...");
        for (int i = 0; i < javaFiles.size(); i++) {
            String filePath = javaFiles.get(i);
            char[] source = sources.get(i);

            // Create and configure the parser
            ASTParser parser = ASTParser.newParser(AST.JLS8);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            parser.setEnvironment(classpathEntries, sourcepathEntries, new String[] { "UTF-8" }, true);
            parser.setUnitName(new File(filePath).getName());
            parser.setSource(source);

            CompilationUnit compilationUnit = (CompilationUnit) parser.createAST(null);
            compilationUnits.add(compilationUnit);
            
            System.out.println("Parsed: " + new File(filePath).getName());
        }

        // SECOND PASS: Resolve bindings and create syntax trees
        System.out.println("Second pass: Resolving bindings and creating syntax trees...");
        for (int i = 0; i < javaFiles.size(); i++) {
            String filePath = javaFiles.get(i);
            char[] source = sources.get(i);
            CompilationUnit compilationUnit = compilationUnits.get(i);
            
            // Collect name resolution edges for this compilation unit
            List<Edge> nameResEdges = new ArrayList<>();
            compilationUnit.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    IBinding binding = node.resolveBinding();
                    String usageId = node.getClass().getSimpleName() + "@" + node.getStartPosition();
                    int usageIdNum = node.hashCode();

                    if (binding != null) {
                        // Use findDeclaringNode to check if the binding resolves to a node in this compilation unit
                        ASTNode declaringNode = compilationUnit.findDeclaringNode(binding);
                        
                        if (declaringNode != null) {
                            // The binding resolves to a node in this compilation unit
                            String defId = binding.getKey();
                            int defIdNum = declaringNode.hashCode();

                            nameResEdges.add(new Edge(usageId, defId, usageIdNum, defIdNum));
                            System.out.println("Added edge: " + usageId + " -> " + defId + " (defIdNum: " + defIdNum + ")");
                        } else {
                            // The binding does not resolve to a node in this compilation unit - skip it
                            System.out.println("Skipped binding for " + usageId + " - not resolved in current unit");
                        }
                    }
                    
                    return super.visit(node);
                }
            });

            // Create syntax tree for this compilation unit
            SyntaxVisitor syntaxVisitor = new SyntaxVisitor(compilationUnit, source);
            compilationUnit.accept(syntaxVisitor);
            Map<String, Object> syntax = syntaxVisitor.syntaxMap;

            filesDetails.add(new FileDetails(filePath, nameResEdges, syntax));
            System.out.println("Processed: " + new File(filePath).getName() + " (" + nameResEdges.size() + " edges)");
        }

        return filesDetails;
    }
}