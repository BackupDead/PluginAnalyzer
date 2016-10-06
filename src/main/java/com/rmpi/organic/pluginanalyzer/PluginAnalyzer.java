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
	private String pluginName = "ã�� �� �����ϴ�";
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
		getLogger().info(getClass().getSimpleName() + " �� Ȱ��ȭ �Ǿ����ϴ�!");
		reloadExceptions(getServer().getConsoleSender());
		analyze(getServer().getConsoleSender());
	}
	
	@Override
	public void onDisable() {
		getLogger().info(getClass().getSimpleName() + " �� ��Ȱ��ȭ�Ǿ����ϴ�!");
	}
	
	@SuppressWarnings("deprecation")
	private void reloadExceptions(CommandSender sender) {
		getDataFolder().mkdirs();
		Config exceptions;
		
		try {
			exceptions = new Config(new File(getDataFolder(), EXCEPTIONS_FILE), Config.JSON, EXCEPTIONS_DEFAULT);
		} catch (JsonSyntaxException e) {
			exceptions = new Config(Config.JSON);
			sender.sendMessage("���� �÷����� ��� ������ �������ϴ�. �ʱ�ȭ�� �����մϴ�.");
			exceptions.setAll(EXCEPTIONS_DEFAULT);
			exceptions.save(new File(getDataFolder(), EXCEPTIONS_FILE));
			return;
		}
		
		if (exceptions.isList(EXCEPTIONS_KEY))
			this.exceptions = exceptions.getStringList(EXCEPTIONS_KEY);
		else {
			sender.sendMessage("���� �÷����� ��� ������ �������ϴ�. �ʱ�ȭ�� �����մϴ�.");
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
			pluginName = "ã�� �� �����ϴ�";
			
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
				pluginName = "ã�� �� �����ϴ�";
			}
			
			if (isOpTrigger || isGamemodeTrigger || isPermissionTrigger || isReflectAccess || isBytecodeManipulate || isShellAccess) {
				sender.sendMessage(plugin.getName() + " �÷����� ������ " + TextFormat.RED + "����" + TextFormat.WHITE + "�� �� �ִ� �ڵ带 �����ϰ� �ֽ��ϴ�.");
				sender.sendMessage("�ŷ� ������ �÷��������� ������ ������ ������.");
				sender.sendMessage("�÷����� �̸�: " + TextFormat.RED + pluginName + TextFormat.WHITE);
				sender.sendMessage("���輺 ���:");
				if (isOpTrigger)
					sender.sendMessage("�� �÷������� " + TextFormat.RED + "����" + TextFormat.WHITE + " ������ �����մϴ�.");
				if (isGamemodeTrigger)
					sender.sendMessage("�� �÷������� " + TextFormat.RED + "���Ӹ��" + TextFormat.WHITE + "�� �����մϴ�.");
				if (isPermissionTrigger)
					sender.sendMessage("�� �÷������� " + TextFormat.RED + "�۹̼�" + TextFormat.WHITE + "�� �����մϴ�.");
				if (isCommandDispatch)
					sender.sendMessage("�� �÷������� " + TextFormat.RED + "��ɾ�" + TextFormat.WHITE + "�� ����մϴ�.");
				if (isServerPropertyTrigger)
					sender.sendMessage("�� �÷������� " + TextFormat.RED + "���� ������Ƽ" + TextFormat.WHITE + "�� �����մϴ�.");
				if (isReflectAccess)
					sender.sendMessage("�� �÷������� " + TextFormat.RED + "���÷���" + TextFormat.WHITE + "�� ����մϴ�.");
				if (isBytecodeManipulate)
					sender.sendMessage("�� �÷������� " + TextFormat.RED + "����Ʈ�ڵ� ������ ó��" + TextFormat.WHITE + "�� ����մϴ�.");
				if (isShellAccess)
					sender.sendMessage("�� �÷������� " + TextFormat.RED + "��" + TextFormat.WHITE + "�� ����� �����ϴ�.");
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
			case "�÷����κм�":
				sender.sendMessage("�м��� �����մϴ�.");
				analyze(sender);
				break;
			case "���ܸ�ϸ��ε�":
				sender.sendMessage("���� �÷����� ��� ������ ���ε��մϴ�.");
				reloadExceptions(sender);
				break;
		}
		
		return true;
	}
}
