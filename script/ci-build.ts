import { execSync } from "child_process";
import { rmSync, mkdirSync } from "fs";
import path from "path";

async function ciBuild() {
  console.log("CI Build: Building for Capacitor Android...");
  
  // Clean dist
  rmSync("dist", { recursive: true, force: true });
  mkdirSync("dist/public", { recursive: true });
  
  console.log("Building client with Vite...");
  // Run vite build with CI-specific config
  execSync("npx vite build --config vite.config.ci.ts", { 
    cwd: path.join(process.cwd(), "client"),
    stdio: "inherit",
    env: { ...process.env, NODE_ENV: "production" }
  });
  
  console.log("Client build complete!");
}

ciBuild().catch((err) => {
  console.error(err);
  process.exit(1);
});
