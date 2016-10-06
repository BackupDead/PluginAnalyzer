package com.rmpi.organic.pluginanalyzer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.google.gson.JsonSyntaxException;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.ConsoleCommandSender;
import cn.nukkit.lang.TranslationContainer;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import cn.nukkit.utils.TextFormat;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

public class PluginAnalyzer extends PluginBase {
	private static final String EXCEPTIONS_FILE = "exceptions.json";
	private static final String EXCEPTIONS_KEY = "exceptions";
	private static final LinkedHashMap<String, Object> EXCEPTIONS_DEFAULT;
	
	static {
		EXCEPTIONS_DEFAULT = new LinkedHashMap<>();
		EXCEPTIONS_DEFAULT.put(EXCEPTIONS_KEY, new ArrayList<String>());
	}
	
	private List<String> exceptions = new ArrayList<>();
	// GLOBAL_LIKE FIELDS
	private String pluginName = "찾을 수 없습니다";
	private boolean isOpTrigger = false;
	private boolean isGamemodeTrigger = false;
	private boolean isPermissionTrigger = false;
	private boolean isCommandDispatch = false;
	private boolean isServerPropertyTrigger = false;
	
	private boolean isReflectAccess = false;
	private boolean isBytecodeManipulate = false;
	private boolean isShellAccess = false;
	private ZipFile pluginZip = null;
	
	@Override
	public void onEnable() {
		getLogger().info(getClass().getSimpleName() + " 이 활성화 되었습니다!");
		reloadExceptions(getServer().getConsoleSender());
		analyze(getServer().getConsoleSender());
	}
	
	@Override
	public void onDisable() {
		getLogger().info(getClass().getSimpleName() + " 이 비활성화되었습니다!");
	}
	
	@SuppressWarnings("deprecation")
	private void reloadExceptions(CommandSender sender) {
		getDataFolder().mkdirs();
		Config exceptions;
		
		try {
			exceptions = new Config(new File(getDataFolder(), EXCEPTIONS_FILE), Config.JSON, EXCEPTIONS_DEFAULT);
		} catch (JsonSyntaxException e) {
			exceptions = new Config(Config.JSON);
			sender.sendMessage("예외 플러그인 목록 파일이 깨졌습니다. 초기화를 진행합니다.");
			exceptions.setAll(EXCEPTIONS_DEFAULT);
			exceptions.save(new File(getDataFolder(), EXCEPTIONS_FILE));
			return;
		}
		
		if (exceptions.isList(EXCEPTIONS_KEY))
			this.exceptions = exceptions.getStringList(EXCEPTIONS_KEY);
		else {
			sender.sendMessage("예외 플러그인 목록 파일이 깨졌습니다. 초기화를 진행합니다.");
			exceptions.setAll(EXCEPTIONS_DEFAULT);
			exceptions.save();
		}
	}
	
	@SuppressWarnings("unchecked")
	private void analyze(CommandSender sender) {
		ClassPool defaultPool = ClassPool.getDefault();
		for (File plugin : getDataFolder().getParentFile()
				.listFiles(f -> {
					boolean isDiffer = true;
					
					try {
						isDiffer = !f.getCanonicalPath().equals(getFile().getCanonicalPath());
					} catch (IOException e) {
						
					}
					
					return f.getName().endsWith(".jar") && isDiffer && !exceptions.contains(f.getName()); 
				})) {
			try {
				defaultPool.insertClassPath(plugin.getAbsolutePath());
			} catch (NotFoundException e1) {
				
			}
			
			pluginZip = null;
			
			try {
				pluginZip = new ZipFile(plugin);
			} catch (IOException e) {
				continue;
			}
			
			isOpTrigger = false;
			isGamemodeTrigger = false;
			isPermissionTrigger = false;
			isCommandDispatch = false;
			isServerPropertyTrigger = false;
			isReflectAccess = false;
			isBytecodeManipulate = false;
			isShellAccess = false;
			Collections.list(pluginZip.entries()).parallelStream()
				.filter(z -> !z.isDirectory() && z.getName().endsWith(".class"))
				.map(z -> z.getName().substring(0, z.getName().lastIndexOf('.')).replace('/', '.'))
				.forEach(s -> {
					try {
						CtClass currentClass = defaultPool.getCtClass(s);
						currentClass.instrument(new ExprEditor() {
							@Override
							public void edit(MethodCall call) {
								if (call.getClassName().startsWith("cn.nukkit.")) { 
									if (call.getMethodName().equals("setOp")
										|| call.getMethodName().equals("addOp")
										|| call.getMethodName().equals("removeOp"))
										isOpTrigger = true;
									if (call.getMethodName().equals("setGamemode"))
										isGamemodeTrigger = true;
									if (call.getMethodName().equals("addAttachment")
										|| call.getMethodName().equals("removeAttachment"))
										isPermissionTrigger = true;
									if (call.getMethodName().equals("dispatchCommand"))
										isCommandDispatch = true;
									
									try {
										if (defaultPool.getCtClass(call.getClassName()).subclassOf(defaultPool.getCtClass("cn.nukkit.permission.PermissionAttachment")))
											if (call.getMethodName().equals("setPermission")
												|| call.getMethodName().equals("unsetPermission")
												|| call.getMethodName().equals("setPermissions")
												|| call.getMethodName().equals("unsetPermissions")
												|| call.getMethodName().equals("clearPermissions")
												|| call.getMethodName().equals("remove"))
												isPermissionTrigger = true;
									} catch (NotFoundException e) {
										
									}
									
									try {
										if (defaultPool.getCtClass(call.getClassName()).subclassOf(defaultPool.getCtClass("cn.nukkit.Server")))
											if (call.getMethodName().startsWith("setProperty"))
												isServerPropertyTrigger = true;
									} catch (NotFoundException e) {
										
									}
								}
								
								try {
									if (defaultPool.getCtClass(call.getClassName()).subclassOf(defaultPool.getCtClass("java.lang.Runtime")))
										if (call.getMethodName().equals("exec"))
											isShellAccess = true;
								} catch (NotFoundException e) {
									
								}
							}
						});
						Collection<String> refClasses = currentClass.getRefClasses();
						
						for (String className : refClasses) {
							if (className.startsWith("java.lang.reflect."))
								isReflectAccess = true;
							if (className.startsWith("javassist."))
								isBytecodeManipulate = true;
						}
						
					} catch (NotFoundException | CannotCompileException e) {
						
					}
				});
			pluginName = "찾을 수 없습니다";
			
			try {
				ZipEntry descEntry = pluginZip.getEntry("nukkit.yml");
				if (descEntry == null) descEntry = pluginZip.getEntry("plugin.yml");
				
				if (descEntry != null) {
					try (InputStream zin = pluginZip.getInputStream(descEntry)) {
						DumperOptions dumperOptions = new DumperOptions();
				        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
				        Yaml yamlParser = new Yaml(dumperOptions);
						LinkedHashMap<String, Object> keys = yamlParser.loadAs(zin, LinkedHashMap.class);
						if (keys.containsKey("name") && keys.get("name") instanceof String)
							pluginName = (String) keys.get("name");
					} catch (Exception e) {
						
					}
				}
			} catch (Exception e) {
				pluginName = "찾을 수 없습니다";
			}
			
			if (isOpTrigger || isGamemodeTrigger || isPermissionTrigger || isReflectAccess || isBytecodeManipulate || isShellAccess) {
				sender.sendMessage(plugin.getName() + " 플러그인 파일은 " + TextFormat.RED + "위험" + TextFormat.WHITE + "할 수 있는 코드를 내포하고 있습니다.");
				sender.sendMessage("신뢰 가능한 플러그인인지 신중히 생각해 보세요.");
				sender.sendMessage("플러그인 이름: " + TextFormat.RED + pluginName + TextFormat.WHITE);
				sender.sendMessage("위험성 목록:");
				if (isOpTrigger)
					sender.sendMessage("이 플러그인은 " + TextFormat.RED + "오피" + TextFormat.WHITE + " 정보를 수정합니다.");
				if (isGamemodeTrigger)
					sender.sendMessage("이 플러그인은 " + TextFormat.RED + "게임모드" + TextFormat.WHITE + "를 변경합니다.");
				if (isPermissionTrigger)
					sender.sendMessage("이 플러그인은 " + TextFormat.RED + "퍼미션" + TextFormat.WHITE + "을 변경합니다.");
				if (isCommandDispatch)
					sender.sendMessage("이 플러그인은 " + TextFormat.RED + "명령어" + TextFormat.WHITE + "를 사용합니다.");
				if (isServerPropertyTrigger)
					sender.sendMessage("이 플러그인은 " + TextFormat.RED + "서버 프로퍼티" + TextFormat.WHITE + "를 변경합니다.");
				if (isReflectAccess)
					sender.sendMessage("이 플러그인은 " + TextFormat.RED + "리플렉션" + TextFormat.WHITE + "을 사용합니다.");
				if (isBytecodeManipulate)
					sender.sendMessage("이 플러그인은 " + TextFormat.RED + "바이트코드 수준의 처리" + TextFormat.WHITE + "를 사용합니다.");
				if (isShellAccess)
					sender.sendMessage("이 플러그인은 " + TextFormat.RED + "쉘" + TextFormat.WHITE + "에 명령을 내립니다.");
			}
		}
	}
	
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!command.testPermission(sender))
			return true;
		
		if (!(sender instanceof ConsoleCommandSender)) {
			sender.sendMessage(new TranslationContainer(TextFormat.RED + "%commands.generic.permission"));
			return true;
		}
		
		switch (command.getName()) {
			case "플러그인분석":
				sender.sendMessage("분석을 진행합니다.");
				analyze(sender);
				break;
			case "예외목록리로드":
				sender.sendMessage("예외 플러그인 목록 파일을 리로드합니다.");
				reloadExceptions(sender);
				break;
		}
		
		return true;
	}
}
