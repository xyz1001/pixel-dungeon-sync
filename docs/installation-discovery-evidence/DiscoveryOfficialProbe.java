import pdsync.*;
import java.nio.file.*;
import java.util.*;
public final class DiscoveryOfficialProbe {
 public static void main(String[] args) throws Exception {
  Path area = Files.createTempDirectory("pds-discovery-official-");
  Path install = Files.createDirectories(area.resolve("install"));
  Path jar = Files.copy(Path.of(args[0]), install.resolve("ShatteredPD-first.jar"));
  var context = new SavePathContext("Linux", area.resolve("home").toString(), Map.of());
  var scanner = new ShatteredInstallationDiscovery(context,
    List.of(new InstallationSearchRoot(install.toString(), "isolated fixture copy", List.of(""), false)));
  var first = scanner.discover("");
  if(first.getSuggestedTarget() == null || !first.getSuggestedTarget().getTrusted()) throw new AssertionError("official JAR not trusted");
  Files.copy(jar, install.resolve("ShatteredPD-second.jar"));
  var second = scanner.discover("");
  if(second.getTrustedTargets().size()!=2 || second.getSuggestedTarget()!=null || !second.getRequiresSelection()) throw new AssertionError("ambiguous result selected");
  if(!scanner.validateJar(jar).getTrusted()) throw new AssertionError("manual official JAR failed");
  System.out.println("OFFICIAL_DISCOVERY_STATIC_OK version="+first.getSuggestedTarget().getDisplayVersion()+" code="+first.getSuggestedTarget().getVersionCode());
  System.out.println("sha256="+first.getSuggestedTarget().getSha256());
  System.out.println("oneTrusted=suggested twoTrusted=requiresSelection manual=trusted gameExecution=none");
  System.out.println("defaultSaveCreated="+Files.exists(area.resolve("home")));
 }
}
