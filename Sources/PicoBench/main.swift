@main
enum PicoBenchMain {
	static func main() {
		let args = Array(CommandLine.arguments.dropFirst())
		if args.contains("--smoke") {
			let runs = args.firstIndex(of: "--runs").flatMap { idx in
				args.index(after: idx) < args.endIndex ? args[args.index(after: idx)] : nil
			} ?? "1"
			print(#"{"mode":"smoke","runs":\#(runs)}"#)
		}
	}
}
