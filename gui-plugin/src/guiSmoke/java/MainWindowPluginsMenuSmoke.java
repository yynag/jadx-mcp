import java.nio.file.*;
import java.util.*;
import java.util.prefs.*;
import javax.swing.*;
import jadx.api.*;
import jadx.api.plugins.*;
import jadx.api.plugins.loader.*;
import jadx.core.plugins.files.*;
import jadx.gui.*;
import jadx.gui.settings.*;
import jadx.gui.ui.*;
import jadx.gui.utils.*;
import jadx.plugins.tools.*;

/** Real release GUI + external ServiceLoader regression; deliberately no desktop handlers,
 * user config, user Preferences, APK, or host GUI process access. Run via guiMenuSmoke.
 */
public class MainWindowPluginsMenuSmoke {
 private static final String TITLE="Jadx MCP";
 public static void main(String[] args) throws Exception {
  int result=1;
  try { run(args); result=0; } catch(Throwable e) { e.printStackTrace(); }
  System.exit(result); // Only this isolated smoke JVM, never an existing application.
 }
 private static void run(String[] args) throws Exception {
  Path root=Path.of(args[0]).toAbsolutePath(); Files.createDirectories(root);
  System.setProperty("user.home", root.toString());
  System.setProperty("java.util.prefs.PreferencesFactory", MemoryPreferencesFactory.class.getName());
  var adapter=JadxSettings.buildConfigAdapter(); adapter.useConfigRef(root.resolve("gui.json").toString());
  var settings=new JadxSettings(adapter);
  var data=new JadxSettingsData(); data.setCheckForUpdates(false); data.setCacheDir(root.resolve("cache").toString());
  settings.loadSettingsData(data);
  final MainWindow[] win=new MainWindow[1];
  SwingUtilities.invokeAndWait(()-> { NLS.setLocale(settings.getLangLocale()); win[0]=new MainWindow(settings); });
  try(var loader=new JadxExternalPluginsLoader()) {
   // loadFromPath, unlike load(), does not inspect the user's installed-plugin registry.
   var plugin=loader.loadFromPath(Path.of(args[1]).toAbsolutePath());
   if(!plugin.getPluginInfo().getPluginId().equals("jadx-mcp-gui")) throw new AssertionError("Wrong provider");
   var jadArgs=new JadxArgs();
   jadArgs.setInputFile(Path.of(args[2]).toAbsolutePath().toFile());
   jadArgs.setFilesGetter(new IJadxFilesGetter() {
    public Path getConfigDir(){return root.resolve("config");}
    public Path getCacheDir(){return root.resolve("cache");}
    public Path getTempDir(){return root.resolve("tmp");}
   });
   jadArgs.setPluginLoader(new JadxPluginLoader() {
    public List<JadxPlugin> load(){var list=new ArrayList<>(new JadxBasePluginLoader().load()); list.add(loader.loadFromPath(Path.of(args[1]).toAbsolutePath())); return list;}
    public void close(){}
   });
   try(var decompiler=new JadxDecompiler(jadArgs)) {
    var gui=JadxWrapper.initGuiPluginsContext(decompiler,win[0]);
    decompiler.load(); // The actual decompiler load -> listener -> PluginContext.init path.
    var context=decompiler.getPluginManager().getResolvedPluginContexts().stream()
       .filter(c->c.getPluginId().equals("jadx-mcp-gui")).findFirst().orElseThrow();
    if(context.getGuiContext()==null || !context.isInitialized()) throw new AssertionError("GUI init skipped");
     flush(); checkMenu(win[0],"project load");
     checkSettingsWithoutBoundProject(win[0]);
    SwingUtilities.invokeAndWait(gui::reset);
    flush(); checkMenu(win[0],"CommonGuiPluginsContext.reset");
    SwingUtilities.invokeAndWait(()-> { win[0].resetPluginsMenu(); win[0].resetPluginsMenu(); });
    flush(); checkMenu(win[0],"repeated MainWindow.resetPluginsMenu");
    SwingUtilities.invokeAndWait(gui::reset);
    decompiler.reloadPasses(); // Mirrors wrapper reset -> unload -> new provider -> init.
    flush(); checkMenu(win[0],"real unload/reloadPasses");
    System.out.println("PASS: external provider, real decompiler load, non-null GUI context, initialized plugin, menu after both real resets and unload/reload, no duplicates");
   }
  } finally { SwingUtilities.invokeAndWait(()->win[0].dispose()); }
 }
  private static void flush() throws Exception { for(int i=0;i<4;i++) SwingUtilities.invokeAndWait(()->{}); }
  /** The decompiler exists, but MainWindow.open/onLoaded never bound a plugin project runtime.
   * This mirrors configuring from the user's Waiting for project window, not a fake HTTP port.
   */
  private static void checkSettingsWithoutBoundProject(MainWindow win) throws Exception {
   SwingUtilities.invokeAndWait(()-> {
    for(var c:win.getPluginsMenu().getMenuComponents())if(c instanceof JMenuItem item&&item.getText().equals(TITLE))item.doClick();
   });
   flush();
   final JDialog[] dialog={null};
   long openDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
   while(dialog[0]==null&&System.nanoTime()<openDeadline) {
    SwingUtilities.invokeAndWait(()-> {
     for(var w:java.awt.Window.getWindows())if(w instanceof JDialog d&&d.getTitle().equals(TITLE)&&d.isDisplayable())dialog[0]=d;
    });
    if(dialog[0]==null)Thread.sleep(20); // Host menu actions dispatch through a background executor.
   }
   SwingUtilities.invokeAndWait(()-> {
    if(dialog[0]==null)throw new AssertionError("No unified window");
    var inputs=new ArrayList<JTextField>();collect(dialog[0],JTextField.class,inputs);
    if(inputs.size()!=2)throw new AssertionError("Expected IP/port inputs");
    inputs.get(0).setText("127.0.0.1");inputs.get(1).setText("9010");
    var buttons=new ArrayList<JButton>();collect(dialog[0],JButton.class,buttons);
    buttons.stream().filter(b->b.getText().equals("Apply")).findFirst().orElseThrow().doClick();
   });
   boolean[] passed={false};long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
   while(!passed[0]&&System.nanoTime()<deadline) {
    SwingUtilities.invokeAndWait(()-> {
     var labels=new ArrayList<JLabel>();collect(dialog[0],JLabel.class,labels);
      passed[0]=labels.stream().anyMatch(l->l.getText().equals("Applied!"));
      if(passed[0]&&labels.stream().noneMatch(l->l.getText().contains("DISABLED: Waiting for project")))throw new AssertionError("Unexpected project/network activation");
      var buttons=new ArrayList<JButton>();collect(dialog[0],JButton.class,buttons);
      if(passed[0]&&buttons.stream().filter(b->b.getText().equals("Reconnect")).anyMatch(JButton::isEnabled))throw new AssertionError("Reconnect must be disabled without a bound project");
    });
    if(!passed[0])Thread.sleep(20); // Bounded asynchronous UI acknowledgement only, never business readback.
   }
   if(!passed[0])throw new AssertionError("No-project IP/9010 Apply did not succeed");
   SwingUtilities.invokeAndWait(()->dialog[0].dispose());
   System.out.println("PASS: no bound project, IP127.0.0.1/9010 saves as Applied!, remains Waiting for project");
  }
  private static <T extends java.awt.Component> void collect(java.awt.Container parent,Class<T> type,List<T> results) {
   for(var child:parent.getComponents())if(type.isInstance(child))results.add(type.cast(child));else if(child instanceof java.awt.Container c)collect(c,type,results);
  }
  private static void checkMenu(MainWindow win,String stage) throws Exception {
  SwingUtilities.invokeAndWait(()-> {
   long count=Arrays.stream(win.getPluginsMenu().getMenuComponents()).filter(c->c instanceof JMenuItem && ((JMenuItem)c).getText().equals(TITLE)).count();
    if(count!=1) throw new AssertionError(stage+": expected one configuration action, got "+count);
    long bridgeCount=Arrays.stream(win.getPluginsMenu().getMenuComponents()).filter(c->c instanceof JMenuItem && ((JMenuItem)c).getText().toLowerCase().contains("mcp")).count();
    if(bridgeCount!=1) throw new AssertionError(stage+": expected sole Jadx MCP entry, got "+bridgeCount);
   System.out.println("MENU "+stage+"="+Arrays.toString(Arrays.stream(win.getPluginsMenu().getMenuComponents()).map(c->c instanceof JMenuItem?((JMenuItem)c).getText():"separator").toArray()));
  });
 }
 public static class MemoryPreferencesFactory implements PreferencesFactory {
  private final Preferences root=new MemoryPreferences(null,"");
  public Preferences userRoot(){return root;} public Preferences systemRoot(){return root;}
 }
 private static class MemoryPreferences extends AbstractPreferences {
  private final Map<String,String> values=new HashMap<>();
  MemoryPreferences(AbstractPreferences p,String n){super(p,n);}
  protected void putSpi(String k,String v){values.put(k,v);}
   protected String getSpi(String k){
    if(k.equals("masterUrl")&&!values.containsKey(k))return System.getProperty("jadx.mcp.smoke.savedOrigin");
    return values.get(k);
   }
  protected void removeSpi(String k){values.remove(k);}
  protected void removeNodeSpi(){values.clear();}
  protected String[] keysSpi(){return values.keySet().toArray(new String[0]);}
  protected String[] childrenNamesSpi(){return new String[0];}
  protected AbstractPreferences childSpi(String n){return new MemoryPreferences(this,n);}
   protected void syncSpi(){}
   protected void flushSpi() throws BackingStoreException {
    if(Boolean.getBoolean("jadx.mcp.smoke.failPreferences"))throw new BackingStoreException("injected-private-detail");
   }
 }
}
