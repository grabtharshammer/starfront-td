import UIKit
import StarfrontKit

/// Hosts the game (built in Kotlin, see ios/ in the repo) full screen, and locks the screen to the
/// orientation the game asks for: portrait in its vertical mode, landscape otherwise.
class GameViewController: UIViewController {
    private var vertical = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        let game = MainViewControllerKt.MainViewController(setVertical: { [weak self] vertical in
            self?.setVertical(vertical.boolValue)
        })
        addChild(game)
        game.view.frame = view.bounds
        game.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(game.view)
        game.didMove(toParent: self)
    }

    private func setVertical(_ value: Bool) {
        vertical = value
        setNeedsUpdateOfSupportedInterfaceOrientations()
        view.window?.windowScene?.requestGeometryUpdate(.iOS(interfaceOrientations: supportedInterfaceOrientations))
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { vertical ? .portrait : .landscape }
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    // A swipe from an edge goes to the game first; a second swipe reaches the system.
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }
}
