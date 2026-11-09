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
  protected String getSpi(String k){return values.get(k);}
  protected void removeSpi(String k){values.remove(k);}
  protected void removeNodeSpi(){values.clear();}
  protected String[] keysSpi(){return values.keySet().toArray(new String[0]);}
  protected String[] childrenNamesSpi(){return new String[0];}
  protected AbstractPreferences childSpi(String n){return new MemoryPreferences(this,n);}
  protected void syncSpi(){} protected void flushSpi(){}
 }
}
