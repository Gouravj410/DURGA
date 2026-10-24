const { app } = require("../server");
const path = require("path");
const fs = require("fs");

const PORT = 3000;

// Clean up existing test database before test run if needed
const testDbPath = path.join(__dirname, "..", "durga.db");
if (fs.existsSync(testDbPath)) {
  try {
    fs.unlinkSync(testDbPath);
  } catch (e) {
    // In case file is locked
  }
}

const server = app.listen(PORT, async () => {
  console.log(`[TEST HARNESS] Temporary test server started on port ${PORT}`);
  try {
    // Run tests
    require("./backend_test");
  } catch (err) {
    console.error("[TEST HARNESS] Error loading backend_test:", err);
    server.close();
    process.exit(1);
  }
});

process.on("exit", () => {
  server.close();
});
