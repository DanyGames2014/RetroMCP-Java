package org.mcphackers.mcp.tasks;

import static org.mcphackers.mcp.MCPPaths.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import org.mcphackers.mcp.MCP;
import org.mcphackers.mcp.MCPPaths;
import org.mcphackers.mcp.tasks.mode.TaskParameter;
import org.mcphackers.mcp.tools.FileUtil;
import org.mcphackers.mcp.tools.mappings.MappingUtil;
import org.mcphackers.rdi.injector.RDInjector;
import org.mcphackers.rdi.injector.data.ClassStorage;
import org.mcphackers.rdi.injector.data.Mappings;
import org.mcphackers.rdi.util.ClassStorageWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

public class TaskReobfuscate extends TaskStaged {

	public TaskReobfuscate(Side side, MCP instance) {
		super(side, instance);
	}

	public TaskReobfuscate(Side side, MCP instance, ProgressListener listener) {
		super(side, instance, listener);
	}

	@Override
	protected Stage[] setStages() {
		return new Stage[] {
			stage(getLocalizedStage("gathermd5"),
			() -> new TaskUpdateMD5(side, mcp, this).updateMD5(true)),
			stage(getLocalizedStage("reobf"), 43,
					this::reobfuscate)
		};
	}


	private void reobfuscate() throws IOException {
		final Path reobfBin = MCPPaths.get(mcp, BIN, side);

		Side[] sides = side == Side.MERGED ? new Side[] {Side.CLIENT, Side.SERVER} : new Side[] {side};

		Map<String, String> originalHashes = gatherMD5Hashes(false);
		Map<String, String> recompHashes = gatherMD5Hashes(true);

		for(Side localSide : sides) {

			final Path reobfDir = MCPPaths.get(mcp, REOBF_SIDE, localSide);
			final Path reobfJar = MCPPaths.get(mcp, REOBF_JAR, localSide);
			List<String> classNames = new ArrayList<>();
			try (Stream<Path> paths = Files.walk(reobfBin)) {
				paths.forEach(path -> {
					if(path.getFileName().toString().endsWith(".class")) {
						ClassReader classReader;
						try {
							classReader = new ClassReader(Files.readAllBytes(path));
							classNames.add(classReader.getClassName());
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				});
			}
			Files.deleteIfExists(reobfJar);
			RDInjector injector = new RDInjector(reobfBin);
			Mappings mappings = getMappings(injector.getStorage(), localSide);
			if(mappings != null) {
				injector.applyMappings(mappings);
			}
			injector.transform();
			new ClassStorageWriter(injector.getStorage(), ClassWriter.COMPUTE_MAXS).write(Files.newOutputStream(reobfJar));

			Map<String, String> reversedNames = new HashMap<>();
			for(Entry<String, String> entry : mappings.classes.entrySet()) {
				reversedNames.put(entry.getValue(), entry.getKey());
			}
			FileUtil.cleanDirectory(reobfDir);
			FileUtil.extract(reobfJar, reobfDir, entry -> {
				if(entry.isDirectory()) {
					return false;
				}
				String className = entry.getName().replace(".class", "");
				// Force inner classes to compare outer class hash
				int index;
				if((index = className.indexOf('$')) != -1) {
					className = className.substring(0, index);
				}
				String deobfName = reversedNames.get(className);
				if(deobfName == null) {
					deobfName = className;
				}
				String hash			= originalHashes.get(deobfName);
				String hashModified = recompHashes.get(deobfName);
				if(hash == null) {
					return true;
				}
				else return !hash.equals(hashModified);
			});
		}
	}

	private Mappings getMappings(ClassStorage storage, Side side) throws IOException {
		Path mappingsPath = MCPPaths.get(mcp, MAPPINGS);
		if(!Files.exists(mappingsPath)) {
			return null;
		}
		final boolean enableObfuscation = mcp.getOptions().getBooleanParameter(TaskParameter.OBFUSCATION);
		boolean joined = MappingUtil.readNamespaces(mappingsPath).contains("official");
		Mappings mappings = Mappings.read(mappingsPath, "named", joined ? "official" : side.name);
		modifyClassMappings(mappings, storage.getAllClasses(), enableObfuscation);
		return mappings;
	}

	private void modifyClassMappings(Mappings mappings, List<String> classNames, boolean obf) {
		Map<String, Integer> obfIndexes = new HashMap<>();
		Map<String, String> packageMappings = getPackageMappings(mappings.classes);
		for(String className : classNames) {
			String reobfName = mappings.classes.get(className);
			if (reobfName == null /*&& !hashes.containsKey(className)*/) {
				int i1 = className.indexOf('/');
				String packageName = i1 == -1 ? "" : className.substring(0, i1 + 1);
				String obfPackage = packageMappings.get(packageName);
				String clsName = i1 == -1 ? className : className.substring(i1 + 1);
				if(obf) {
					int obfIndex = obfIndexes.getOrDefault(obfPackage, 0);
					String obfName = MappingUtil.getObfuscatedName(obfIndex);
					List<String> obfNames = new ArrayList<>();
					for(Entry<String, String> entry : mappings.classes.entrySet()) {
						obfNames.add(entry.getValue());
					}
					while(obfNames.contains(obfPackage + obfName)) {
						obfIndex++;
						obfName = MappingUtil.getObfuscatedName(obfIndex);
					}
					if(obfIndex > obfIndexes.getOrDefault(obfPackage, 0)) {
						obfIndexes.put(obfPackage, obfIndex);
					}
					clsName = obfName;
				}
				if(obf || obfPackage != null) {
					String className2 = (obfPackage == null ? packageName : obfPackage) + clsName;
					mappings.classes.put(className, className2);
				}
			}
		}
	}

	private static Map<String, String> getPackageMappings(Map<String, String> classMappings) {
		Map<String, String> packageMappings = new HashMap<>();
		for(Entry<String, String> entry : classMappings.entrySet()) {
			System.out.println(entry.getKey() + " : " + entry.getValue());
		}
		for(Entry<String, String> entry : classMappings.entrySet()) {
			int i1 = entry.getKey().indexOf('/');
			int i2 = entry.getValue().indexOf('/');
			String name1 = i1 == -1 ? "" : entry.getKey().substring(0, i1 + 1);
			String name2 = i2 == -1 ? "" : entry.getKey().substring(0, i2 + 1);
			packageMappings.put(name1, name2);
		}
		return packageMappings;
	}

	@Override
	public void setProgress(int progress) {
		switch (step) {
		case 0: {
			int percent = (int)(progress * 0.42D);
			super.setProgress(1 + percent);
			break;
		}
		default:
			super.setProgress(progress);
			break;
		}
	}

	private Map<String, String> gatherMD5Hashes(boolean reobf) throws IOException {
		final Path md5 = MCPPaths.get(mcp, reobf ? MCPPaths.MD5_RO : MCPPaths.MD5, side);
		Map<String, String> hashes = new HashMap<>();

		try (BufferedReader reader = Files.newBufferedReader(md5)) {
			for(String line = reader.readLine(); line != null; line = reader.readLine()) {
				String[] tokens = line.split(" ");
				hashes.put(tokens[0], tokens[1]);
			}
		}
		return hashes;
	}
}
