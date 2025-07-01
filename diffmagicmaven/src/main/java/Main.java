import org.eclipse.jdt.core.dom.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    // Base directory for input/output files
    private static final String BASE_DIR = ".." + File.separator + ".." + File.separator + "difftastic" + File.separator + "Files" + File.separator;
    
    // Custom PrintStream to capture output
    private static StringBuilder logBuffer = new StringBuilder();
    private static PrintStream originalOut;
    private static PrintStream originalErr;

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
            // Set up logging
            setupLogging();
            
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

            // Save LHS JSON to file
            try (FileWriter file = new FileWriter(BASE_DIR + "lhs.json")) {
                file.write(lhsJsonStr);
                System.out.println("\nLHS JSON saved to: " + BASE_DIR + "lhs.json");
            } catch (IOException e) {
                System.err.println("\nError saving LHS JSON to file: " + e.getMessage());
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


            // Save RHS JSON to file
            try (FileWriter file = new FileWriter(BASE_DIR + "rhs.json")) {
                file.write(rhsJsonStr);
                System.out.println("\nRHS JSON saved to: " + BASE_DIR + "rhs.json");
            } catch (IOException e) {
                System.err.println("\nError saving RHS JSON to file: " + e.getMessage());
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Save log and restore original streams
            saveLogAndRestore();
        }
    }
    
    private static void setupLogging() {
        // Store original streams
        originalOut = System.out;
        originalErr = System.err;
        
        // Create custom PrintStream that writes to both console and buffer
        PrintStream teeOut = new PrintStream(originalOut) {
            @Override
            public void println(String x) {
                originalOut.println(x);
                logBuffer.append(x).append(System.lineSeparator());
            }
            
            @Override
            public void print(String s) {
                originalOut.print(s);
                logBuffer.append(s);
            }
        };
        
        PrintStream teeErr = new PrintStream(originalErr) {
            @Override
            public void println(String x) {
                originalErr.println(x);
                logBuffer.append("ERROR: ").append(x).append(System.lineSeparator());
            }
            
            @Override
            public void print(String s) {
                originalErr.print(s);
                logBuffer.append("ERROR: ").append(s);
            }
        };
        
        // Replace system streams
        System.setOut(teeOut);
        System.setErr(teeErr);
    }
    
    private static void saveLogAndRestore() {
        // Restore original streams first
        System.setOut(originalOut);
        System.setErr(originalErr);
        
        // Save log to file
        try (FileWriter logFile = new FileWriter(BASE_DIR + "log.txt")) {
            logFile.write(logBuffer.toString());
            System.out.println("\nLog saved to: " + BASE_DIR + "log.txt");
        } catch (IOException e) {
            System.err.println("\nError saving log to file: " + e.getMessage());
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
    
    // Helper method to process files with batch parsing approach
    private static List<FileDetails> processFiles(
            List<String> javaFiles,
            int startUniqueId) throws Exception {
        List<FileDetails> filesDetails = new ArrayList<>();

        // Classpath setup
        String[] classpathEntries = { System.getProperty("java.home") + File.separator + "lib" + File.separator + "rt.jar" };
        String[] sourcepathEntries = { new File(javaFiles.get(0)).getParent() };

        SyntaxVisitor.setStartingUniqueId(startUniqueId);

        // Prepare arrays for batch compilation
        String[] sourceFilePaths = javaFiles.toArray(new String[0]);
        String[] sourceEncodings = new String[javaFiles.size()];
        for (int i = 0; i < sourceEncodings.length; i++) {
            sourceEncodings[i] = "UTF-8";
        }
        
        // Encoding array for sourcepath entries (not source files)
        String[] sourcepathEncodings = new String[sourcepathEntries.length];
        for (int i = 0; i < sourcepathEncodings.length; i++) {
            sourcepathEncodings[i] = "UTF-8";
        }

        // Set up compiler options
        Map<String, String> options = new HashMap<>();
        options.put("org.eclipse.jdt.core.compiler.source", "1.8");
        options.put("org.eclipse.jdt.core.compiler.codegen.targetPlatform", "1.8");
        options.put("org.eclipse.jdt.core.compiler.compliance", "1.8");

        // BATCH PARSING: Parse all compilation units together
        System.out.println("\nBatch parsing all compilation units...");
        
        ASTParser parser = ASTParser.newParser(AST.JLS8);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setEnvironment(classpathEntries, sourcepathEntries, sourcepathEncodings, true);
        parser.setCompilerOptions(options);

        // Store compilation units in order
        final List<CompilationUnit> compilationUnits = new ArrayList<>();
        final Map<String, Integer> filePathToIndex = new HashMap<>();
        
        // Build index mapping
        for (int i = 0; i < sourceFilePaths.length; i++) {
            filePathToIndex.put(sourceFilePaths[i], i);
            compilationUnits.add(null); // placeholder
        }

        FileASTRequestor requestor = new FileASTRequestor() {
            @Override
            public void acceptAST(String sourceFilePath, CompilationUnit ast) {
                Integer index = filePathToIndex.get(sourceFilePath);
                if (index != null) {
                    compilationUnits.set(index, ast);
                    System.out.println("Parsed: " + new File(sourceFilePath).getName() + " (index: " + index + ")");
                } else {
                    System.err.println("Warning: Received AST for unknown file: " + sourceFilePath);
                }
            }
        };

        // Perform batch parsing - use sourceEncodings for the source files
        parser.createASTs(sourceFilePaths, sourceEncodings, new String[0], requestor, null);

        // Verify all compilation units were created
        for (int i = 0; i < compilationUnits.size(); i++) {
            if (compilationUnits.get(i) == null) {
                System.err.println("Error: Failed to parse " + sourceFilePaths[i]);
                throw new RuntimeException("Failed to parse file: " + sourceFilePaths[i]);
            }
        }

        // PROCESS: Resolve bindings and create syntax trees
        System.out.println("\nProcessing parsed compilation units...");
        for (int i = 0; i < javaFiles.size(); i++) {
            String filePath = javaFiles.get(i);
            CompilationUnit compilationUnit = compilationUnits.get(i);
            
            // Read source for syntax visitor
            char[] source = new String(Files.readAllBytes(Paths.get(filePath))).toCharArray();
            
            // Collect name resolution edges for this compilation unit
            List<Edge> nameResEdges = new ArrayList<>();
            compilationUnit.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    IBinding binding = node.resolveBinding();
                    String usageId = node.getClass().getSimpleName() + "@" + node.getStartPosition();
                    int usageIdNum = node.hashCode();

                    if (binding != null) {
                        System.out.println("Processing SimpleName: '" + node.getIdentifier() + "' at position " + node.getStartPosition());
                        System.out.println("  Binding type: " + binding.getClass().getSimpleName());
                        System.out.println("  Binding key: " + binding.getKey());
                        
                        // Try to find the declaring node in the current compilation unit first
                        ASTNode declaringNode = compilationUnit.findDeclaringNode(binding);
                        
                        if (declaringNode != null) {
                            // The binding resolves to a node in this compilation unit
                            String defId = binding.getKey();
                            int defIdNum = declaringNode.hashCode();

                            nameResEdges.add(new Edge(usageId, defId, usageIdNum, defIdNum));
                            System.out.println("  Added local edge: " + usageId + " -> " + defId + " (defIdNum: " + defIdNum + ")");
                        } else {
                            // Try to find the declaring node in ALL compilation units
                            ASTNode foundDeclaringNode = null;
                            String foundInFile = null;
                            
                            for (int j = 0; j < compilationUnits.size(); j++) {
                                CompilationUnit otherUnit = compilationUnits.get(j);
                                foundDeclaringNode = otherUnit.findDeclaringNode(binding);
                                if (foundDeclaringNode != null) {
                                    foundInFile = javaFiles.get(j);
                                    break;
                                }
                            }
                            
                            if (foundDeclaringNode != null) {
                                // Found the binding in another compilation unit
                                String defId = binding.getKey();
                                int defIdNum = foundDeclaringNode.hashCode();

                                nameResEdges.add(new Edge(usageId, defId, usageIdNum, defIdNum));
                                System.out.println("  Added cross-file edge: " + usageId + " -> " + defId + " (defIdNum: " + defIdNum + ")");
                                System.out.println("  Found in file: " + new File(foundInFile).getName());
                            } else {
                                // Check if this is a library binding (like System.out.println)
                                if (binding.getKey().startsWith("Ljava/") || binding.getKey().contains("java.lang")) {
                                    System.out.println("  Skipped library binding: " + binding.getKey());
                                } else {
                                    // This might be a binding that should be resolvable but isn't
                                    System.out.println("  WARNING: Binding not resolved in any compilation unit");
                                    System.out.println("  Binding details - Key: " + binding.getKey() + ", Kind: " + binding.getKind());
                                    
                                    // Still add the edge but with -1 as defIdNum to indicate unresolved
                                    String defId = binding.getKey();
                                    nameResEdges.add(new Edge(usageId, defId, usageIdNum, -1));
                                    System.out.println("  Added unresolved edge: " + usageId + " -> " + defId + " (defIdNum: -1)");
                                }
                            }
                        }
                    } else {
                        System.out.println("No binding for SimpleName: '" + node.getIdentifier() + "' at position " + node.getStartPosition());
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