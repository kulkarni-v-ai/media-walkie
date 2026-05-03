import SwiftUI

struct ContentView: View {
    @State private var isTalking = false
    @State private var connectionStatus = "Connecting..."
    @State private var currentChannel = "104.5 MHz"

    var body: some View {
        ZStack {
            Color.black.edgesIgnoringSafeArea(.all)
            
            VStack(spacing: 30) {
                // Status Bar
                HStack {
                    Circle()
                        .fill(connectionStatus == "Connected" ? Color.green : Color.red)
                        .frame(width: 10, height: 10)
                    Text(connectionStatus)
                        .foregroundColor(.white)
                        .font(.system(size: 14, weight: .medium, design: .monospaced))
                    Spacer()
                    Text(currentChannel)
                        .foregroundColor(Color(red: 0, green: 1, blue: 0.8))
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                }
                .padding()
                
                Spacer()
                
                // PTT Button
                Button(action: {
                    // Trigger PTT Start
                }) {
                    ZStack {
                        Circle()
                            .stroke(Color(red: 0, green: 1, blue: 0.8), lineWidth: 4)
                            .frame(width: 220, height: 220)
                        
                        Circle()
                            .fill(isTalking ? Color(red: 0, green: 1, blue: 0.8) : Color.clear)
                            .frame(width: 180, height: 180)
                        
                        Text(isTalking ? "TRANSMITTING" : "PUSH TO TALK")
                            .foregroundColor(isTalking ? .black : .white)
                            .font(.system(size: 20, weight: .black))
                    }
                }
                .pressAction(onPress: {
                    isTalking = true
                    // Start audio capture
                }, onRelease: {
                    isTalking = false
                    // Stop audio capture
                })
                
                Spacer()
                
                // Battery Saving Note
                Text("OLED OPTIMIZED DARK MODE")
                    .foregroundColor(.gray)
                    .font(.system(size: 10, weight: .bold))
                    .padding(.bottom)
            }
        }
    }
}

// Custom View Modifier for PTT behavior
struct PressActions: ViewModifier {
    var onPress: () -> Void
    var onRelease: () -> Void
    
    func body(content: Content) -> some View {
        content
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged({ _ in onPress() })
                    .onEnded({ _ in onRelease() })
            )
    }
}

extension View {
    func pressAction(onPress: @escaping () -> Void, onRelease: @escaping () -> Void) -> some View {
        modifier(PressActions(onPress: onPress, onRelease: onRelease))
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
