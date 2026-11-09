import java.nio.file.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import javax.swing.*;
import java.awt.*;
import jadx.gui.settings.*;
import jadx.gui.ui.*;
import jadx.gui.utils.NLS;

/** Isolated actual GUI host; stdin controls ONLY this JVM. No user settings/windows. */
public class GuiBridgeIntegrationSmoke {
 private static javax.swing.Timer capturedTimer;
 private static int menuListeners;
 public static void main(String[] args) throws Exception {
  try { run(args); System.exit(0); } catch(Throwable t) { t.printStackTrace(); System.exit(1); }
 }
 private static void run(String[] args) throws Exception {
  Path root=Path.of(args[0]).toAbsolutePath(); Files.createDirectories(root);
  System.setProperty("user.home",root.resolve("home").toString());
  System.setProperty("java.util.prefs.PreferencesFactory",MainWindowPluginsMenuSmoke.MemoryPreferencesFactory.class.getName());
  Path dropins=Path.of(System.getenv("JADX_CONFIG_DIR")).resolve("plugins/dropins");
  Files.createDirectories(dropins); Files.copy(Path.of(args[1]),dropins.resolve("jadx-mcp-gui-plugin.jar"),StandardCopyOption.REPLACE_EXISTING);
  var adapter=JadxSettings.buildConfigAdapter(); adapter.useConfigRef(root.resolve("gui.json").toString());
  var settings=new JadxSettings(adapter); var data=new JadxSettingsData();
  data.setCheckForUpdates(false); data.setCacheDir(root.resolve("cache").toString()); settings.loadSettingsData(data);
  MainWindow[] win=new MainWindow[1]; var loaded=new CountDownLatch(1);
  SwingUtilities.invokeAndWait(()-> { NLS.setLocale(settings.getLangLocale()); win[0]=new MainWindow(settings); win[0].addLoadListener(ok->{if(ok)loaded.countDown();return false;}); win[0].init(); win[0].open(Path.of(args[2])); });
  if(!loaded.await(90,TimeUnit.SECONDS))throw new AssertionError("GUI load timeout");
  System.out.println("GUI_READY"); System.out.flush();
  try(var in=new BufferedReader(new InputStreamReader(System.in))) {
   for(String line;(line=in.readLine())!=null;) {
     String command=line;
     if(command.startsWith("native-rename:")) {
      nativeRename(win[0],command.substring("native-rename:".length()));
      System.out.println("DONE "+command); System.out.flush(); continue;
     }
     SwingUtilities.invokeAndWait(()-> {
      if(command.startsWith("load:")) {
       var inputs=Arrays.stream(command.substring(5).split(",")).map(i->Path.of(args[Integer.parseInt(i)+2]).toAbsolutePath().normalize()).toList();
       if(inputs.stream().anyMatch(p->!p.startsWith(root.getParent())))throw new AssertionError("Only owned copied inputs may be loaded");
       win[0].open(inputs); return;
      }
      if(command.startsWith("bridge-name:")) {
       try {
        Object plugin=plugin(win[0]); Object bridge=reference(plugin,"bridge");
        Object runtime=reference(plugin,"runtime");
        long epoch=(Long)runtime.getClass().getMethod("getProjectEpoch").invoke(runtime);
        String name=command.substring("bridge-name:".length());
        if(name.equals("restore"))name=(String)runtime.getClass().getMethod("getProjectName").invoke(runtime);
        bridge.getClass().getMethod("updateProject",long.class,String.class).invoke(bridge,epoch,name);
       }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
       return;
      }
      switch(command) {
       case "plugin-state" -> {
        try {
         Object plugin=plugin(win[0]); Object runtime=reference(plugin,"runtime"); Object bridge=reference(plugin,"bridge");
         System.out.println("PLUGIN_STATE bound="+(runtime!=null)+" configured="+(reference(plugin,"settings")!=null)+" bridge="+(bridge!=null));
         System.out.println("PROJECT_INSTANCE_ID="+reference(plugin,"projectInstanceId"));
         System.out.println("INPUT_NAMES="+win[0].getProject().getFilePaths().stream().map(p->p.getFileName().toString()).toList());
        }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
       }
       case "close-project" -> {
        try { var close=MainWindow.class.getDeclaredMethod("closeAll");close.setAccessible(true);close.invoke(win[0]); }
        catch(ReflectiveOperationException e){throw new RuntimeException(e);}
       }
       case "policy-ask" -> settings.setSaveOption(jadx.gui.settings.data.SaveOptionEnum.ASK);
       case "policy-always" -> settings.setSaveOption(jadx.gui.settings.data.SaveOptionEnum.ALWAYS);
       case "policy-never" -> settings.setSaveOption(jadx.gui.settings.data.SaveOptionEnum.NEVER);
       case "save-owned-project" -> win[0].getProject().saveAs(root.resolve("owned-project.jadx"));
       case "reopen-owned-project" -> win[0].open(root.resolve("owned-project.jadx"));
      case "open" -> { for(var c:win[0].getPluginsMenu().getMenuComponents()) if(c instanceof JMenuItem i && i.getText().equals("Jadx MCP"))i.doClick(); }
      case "reset" -> win[0].resetPluginsMenu();
      case "status" -> { int count=0; for(Window w:Window.getWindows()) if(w instanceof JDialog d && d.isDisplayable() && d.getTitle().equals("Jadx MCP")) {count++;System.out.println("STATUS_VISIBLE modal="+d.isModal()); dump(d.getContentPane());} System.out.println("WINDOW_COUNT="+count); }
      case "close-status" -> {for(Window w:Window.getWindows())if(w instanceof JDialog d && d.getTitle().equals("Jadx MCP"))d.dispose();}
       case "apply", "apply-target" -> {
       for(Window w:Window.getWindows())if(w instanceof JDialog d && d.isDisplayable() && d.getTitle().equals("Jadx MCP")) {
         var fields=new java.util.ArrayList<JTextField>();collectFields(d,fields);
         if(fields.size()!=2)throw new AssertionError("Expected only IP and port inputs");
         var origin=java.net.URI.create(System.getProperty("jadx.mcp.url"));
          fields.get(0).setText(command.equals("apply-target")?System.getProperty("jadx.mcp.smoke.targetIp",origin.getHost()):origin.getHost());
          fields.get(1).setText(command.equals("apply-target")?System.getProperty("jadx.mcp.smoke.targetPort"):Integer.toString(origin.getPort()));
          click(d,"Apply");
        }
       }
       case "reconnect" -> {
         for(Window w:Window.getWindows())if(w instanceof JDialog d && d.isDisplayable() && d.getTitle().equals("Jadx MCP")) click(d,"Reconnect");
        }
       case "invalid-ip", "invalid-port" -> {
       for(Window w:Window.getWindows())if(w instanceof JDialog d && d.isDisplayable() && d.getTitle().equals("Jadx MCP")) {
        var fields=new java.util.ArrayList<JTextField>();collectFields(d,fields);
        fields.get(0).setText(command.equals("invalid-ip")?"http://127.0.0.1":"127.0.0.1");
        fields.get(1).setText(command.equals("invalid-port")?"abc":"8650");
        click(d,"Apply");
       }
      }
      case "capture-cleanup" -> {
       try {
        var plugin=win[0].getWrapper().getDecompiler().getPluginManager().getResolvedPluginContexts().stream().filter(c->c.getPluginId().equals("jadx-mcp-gui")).findFirst().orElseThrow().getPluginInstance();
        var field=plugin.getClass().getDeclaredField("statusWindow");field.setAccessible(true);
        var status=((java.util.concurrent.atomic.AtomicReference<?>)field.get(plugin)).get();
        var timerField=status.getClass().getDeclaredField("timer");timerField.setAccessible(true);
        capturedTimer=(javax.swing.Timer)timerField.get(status);
        if(capturedTimer==null||!capturedTimer.isRunning())throw new AssertionError("Timer not running");
        menuListeners=win[0].getPluginsMenu().getPopupMenu().getContainerListeners().length;
        System.out.println("CAPTURED_TIMER_RUNNING listeners="+menuListeners);
       }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
      }
      case "verify-cleanup" -> {
       int remaining=win[0].getPluginsMenu().getPopupMenu().getContainerListeners().length;
       if(capturedTimer==null||capturedTimer.isRunning()||remaining>menuListeners-1)throw new AssertionError("Timer/listeners leaked");
       System.out.println("CLEANUP_PASS timerStopped=true removedListeners="+(menuListeners-remaining));
      }
      case "verify-timer" -> {if(capturedTimer==null||capturedTimer.isRunning())throw new AssertionError("Closed timer leaked");System.out.println("CLOSED_TIMER_STOPPED");}
      case "unload" -> win[0].getWrapper().close();
      case "quit" -> {win[0].getWrapper().close();win[0].dispose();}
      default -> throw new IllegalArgumentException(command);
     }
    });
    System.out.println("DONE "+command); System.out.flush();
    if(command.equals("quit"))return;
   }
  }
 }
 private static Object plugin(MainWindow win) {
  return win.getWrapper().getDecompiler().getPluginManager().getResolvedPluginContexts().stream()
   .filter(c->c.getPluginId().equals("jadx-mcp-gui")).findFirst().orElseThrow().getPluginInstance();
 }
 private static Object reference(Object plugin,String name) throws ReflectiveOperationException {
  var field=plugin.getClass().getDeclaredField(name);field.setAccessible(true);
  return ((java.util.concurrent.atomic.AtomicReference<?>)field.get(plugin)).get();
 }
 /** Submit the same host event as RenameDialog; wait for native application, never source refresh. */
 private static void nativeRename(MainWindow win,String alias) throws Exception {
  var done=new CompletableFuture<Void>();
  SwingUtilities.invokeAndWait(()-> {
   var cls=win.getWrapper().getDecompiler().searchJavaClassByOrigFullName(System.getProperty("jadx.mcp.smoke.owner","com.yyang.sample.HelloSample"));
   var node=((jadx.gui.treemodel.JRenameNode)win.getCacheObject().getNodeCache().makeFrom(cls)).replace();
   var event=new jadx.api.plugins.events.types.NodeRenamedByUser(node.getJavaNode().getCodeNodeRef(),node.getName(),alias);
   event.setRenameNode(node);
   var events=win.events().global();
   java.util.function.Consumer<jadx.api.plugins.events.types.NodeRenamedByUser> listener=new java.util.function.Consumer<>() {
    public void accept(jadx.api.plugins.events.types.NodeRenamedByUser observed) {
     if(observed!=event)return;
     events.removeListener(jadx.api.plugins.events.JadxEvents.NODE_RENAMED_BY_USER,this);
     SwingUtilities.invokeLater(()-> {
      if(win.getProject().getCodeData().getRenames().stream().noneMatch(r->r.getNewName().equals(alias)))
       done.completeExceptionally(new AssertionError("Native rename was not applied"));
      else done.complete(null);
     });
    }
   };
   events.addListener(jadx.api.plugins.events.JadxEvents.NODE_RENAMED_BY_USER,listener);
   win.events().send(event);
  });
  done.get(30,TimeUnit.SECONDS);
 }
 private static void dump(Container container) {
   for(Component c:container.getComponents()) {if(c instanceof JLabel l)System.out.println("LABEL "+l.getText());else if(c instanceof JButton b)System.out.println("BUTTON "+b.getText()+" enabled="+b.isEnabled());else if(c instanceof Container child)dump(child);}
 }
 private static <T extends Component> T find(Container c,Class<T> type) {
  for(Component child:c.getComponents()) {if(type.isInstance(child))return type.cast(child);if(child instanceof Container nested){T value=find(nested,type);if(value!=null)return value;}}return null;
 }
 private static void collectFields(Container c,java.util.List<JTextField> fields) {
  for(Component child:c.getComponents()){if(child instanceof JTextField f)fields.add(f);else if(child instanceof Container nested)collectFields(nested,fields);}
 }
 private static void click(Container c,String title) {
  for(Component child:c.getComponents()) {if(child instanceof JButton b&&b.getText().equals(title)){b.doClick();return;}if(child instanceof Container nested)click(nested,title);}
 }
}
